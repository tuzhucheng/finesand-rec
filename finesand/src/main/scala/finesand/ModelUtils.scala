package finesand

import java.io.{BufferedWriter,File,FileInputStream,FileOutputStream,FileWriter,ObjectInputStream,ObjectOutputStream}
import java.util.concurrent.ConcurrentHashMap
import scala.collection.convert.decorateAsScala._

import finesand.model.PredictionPoint

object ModelUtils {

  implicit def bool2int(b:Boolean) = if (b) 1 else 0

  val emptySet = collection.mutable.Set.empty[Int]
  val changeContextOccurCache = new ConcurrentHashMap[(String, String, String), Int]().asScala
  val changeContextCooccurCache = new ConcurrentHashMap[((String, String, String),(String,String,String)), Int]().asScala
  val codeContextOccurCache = new ConcurrentHashMap[String, Int]().asScala
  val codeContextCooccurCache = new ConcurrentHashMap[(String, String), Int]().asScala

  def getPredictionPoints(repoCorpus: String, dataset: String, methodSpace: collection.mutable.Set[String] = collection.mutable.Set.empty[String]): Consts.PredictionPointMapType = {
    val partFilePattern = if (dataset == Consts.Train) "predictionPointsPart" else "predictionPointsTestPart"
    val serializedPredictionFiles = new File(repoCorpus).listFiles.filter(f => f.getName contains partFilePattern).map(f => f.getCanonicalPath)
    var predictionPoints: Consts.PredictionPointMapType = collection.mutable.Map()
    serializedPredictionFiles.foreach(f => {
      val ois = new ObjectInputStream(new FileInputStream(f)) {
        override def resolveClass(desc: java.io.ObjectStreamClass): Class[_] = {
          try { Class.forName(desc.getName, false, getClass.getClassLoader) }
          catch { case ex: ClassNotFoundException => super.resolveClass(desc) }
        }
      }
      var currentMap = ois.readObject.asInstanceOf[Consts.PredictionPointMapType]
      if (!methodSpace.isEmpty) {
        currentMap = currentMap.filter { case (trans, pp) => methodSpace.contains(pp.methodName) }
      }
      predictionPoints ++= currentMap
      ois.close
    })
    predictionPoints
  }

  def getChangeContextScore(pp: PredictionPoint, candidate: String, changeContextIndex: Consts.ChangeContextMap, window: Int = 15) : Double = {
    val scoreComps: List[PredictionPoint#ChangeContextScoreComponent] = pp.changeContext.getOrElse(List()).sortWith(_._4 > _._4).take(window)
    val candidateKey = ("INS", "MethodInvocation", candidate)
    val candTransactions = changeContextIndex.getOrElse(candidateKey, emptySet)
    val score = scoreComps.zipWithIndex.map { case (c, i) => {
      val (wScopeCi, wDepCi) = (c._2, c._3)
      val cooccurKey = if (candidateKey._1 < c._1._1) (candidateKey, c._1) else (c._1, candidateKey)
      var nCi = changeContextOccurCache.getOrElse(c._1, -1)
      var nCCi = changeContextCooccurCache.getOrElse(cooccurKey, -1)
      if (nCi == -1 || nCCi == -1) {
        val transactions = changeContextIndex.getOrElse(c._1, emptySet)
        nCi = transactions.size
        changeContextOccurCache.put(c._1, nCi)
        val cooccurTransactions = transactions.intersect(candTransactions)
        nCCi = cooccurTransactions.size
        changeContextCooccurCache.put(cooccurKey, nCCi)
      }
      val dCCi = i+1
      val term = (wScopeCi * wDepCi / dCCi) * Math.log((nCCi.toDouble + 1) / (nCi + 1))
      Math.exp(term)
    }}.sum
    score
  }

  def getCodeContextScore(pp: PredictionPoint, candidate: String, codeContextIndex: Consts.CodeContextMap, window: Int = 15) : Double = {
    val scoreComps: List[PredictionPoint#CodeContextScoreComponent] = pp.codeContext.getOrElse(List()).sortWith(_._4 > _._4).take(window)
    val candTransactions = codeContextIndex.getOrElse(candidate, emptySet)
    val score = scoreComps.zipWithIndex.map { case (c, i) => {
      val (wScopeTi, wDepTi) = (c._2, c._3)
      val cooccurKey = if (candidate < c._1._2) (candidate, c._1._2) else (c._1._2, candidate)
      var nTi = codeContextOccurCache.getOrElse(c._1._2, -1)
      var nCTi = codeContextCooccurCache.getOrElse(cooccurKey, -1)
      if (nTi == -1 || nCTi == -1) {
        val transactions = codeContextIndex.getOrElse(c._1._2, candTransactions)
        nTi = transactions.size
        codeContextOccurCache.put(c._1._2, nTi)
        val cooccurTransactions = transactions.intersect(candTransactions)
        nCTi = cooccurTransactions.size
        codeContextCooccurCache.put(cooccurKey, nCTi)
      }
      val dCTi = i+1
      val term = (wScopeTi * wDepTi / dCTi) * Math.log((nCTi.toDouble + 1) / (nTi + 1))
      Math.exp(term)
    }}.sum
    score
  }

  def getPredictionsAggregateScore(predictionPoints: Consts.PredictionPointMapType, vocab: Array[String], changeContextIndex: Consts.ChangeContextMap, codeContextIndex: Consts.CodeContextMap, wc: Double, k: Int = 10) = {
    val predictions = predictionPoints.toList.par.map { case (_, pp) => {
      val changeContextScores = vocab.map(api => getChangeContextScore(pp, api, changeContextIndex))
      val codeContextScores = vocab.map(api => getCodeContextScore(pp, api, codeContextIndex))
      val scores = (changeContextScores zip codeContextScores).map { case (scoreC, scoreT) => wc*scoreC + (1-wc)*scoreT }
      val topK = (vocab zip scores).sortWith(_._2 > _._2).take(k)
      (pp.methodName, topK)
    }}
    predictions.seq
  }

  def getPredictionsSeparateScore(predictionPoints: Consts.PredictionPointMapType, vocab: Array[String], changeContextIndex: Consts.ChangeContextMap, codeContextIndex: Consts.CodeContextMap, k: Option[Int] = None) = {
    val predictions = predictionPoints.toList.par.map { case (_, pp) => {
      val changeContextScores = vocab.map(api => getChangeContextScore(pp, api, changeContextIndex))
      val codeContextScores = vocab.map(api => getCodeContextScore(pp, api, codeContextIndex))
      val scores = (changeContextScores zip codeContextScores)
      val topK = k match {
        case Some(limit) => (vocab zip scores).sortWith((a, b) => (a._2._1 + a._2._2) > (a._2._1 + a._2._2)).take(limit)
        case None => (vocab zip scores)
      }
      (pp.methodName, topK)
    }}
    predictions.seq
  }

  // Get top-1,2,3,4,5,10 accuracy
  def getAccuracy(predictions: Seq[(String, Array[(String, Double)])], vocab: Array[String]) = {
    val ks = List(1, 2, 3, 4, 5, 10)
    val oovAcc = predictions.map { case (goldMethodName, topK) => {
      ks.map(k => if (topK.toStream.map(_._1).take(k).contains(goldMethodName)) 1 else 0)
    }}
    val oovHits = oovAcc.transpose.map(l => l.reduce(_ + _))
    val oovTotal = List.fill(6)(oovAcc.size)
    val oovFinal = oovHits.zip(oovTotal).map(t => t._1 * 100.0 / t._2).toList

    val inAcc = predictions.filter(kv => vocab.contains(kv._1)).map { case (goldMethodName, topK) => {
      ks.map(k => if (topK.toStream.map(_._1).take(k).contains(goldMethodName)) 1 else 0)
    }}
    val inHits = inAcc.transpose.map(l => l.reduce(_ + _))
    val inTotal = List.fill(6)(inAcc.size)
    val inFinal = inHits.zip(inTotal).map(t => t._1 * 100.0 / t._2).toList
    println("Number of Predictions:", oovAcc.size)
    println("Number of Predictions (in-vocab):", inAcc.size)

    ks.zipWithIndex.map{ case (k, i) => k -> Map("oov" -> oovFinal(i), "in" -> inFinal(i)) }
  }

  def getMAP(predictions: Seq[(String, Array[(String, Double)])], vocab: Array[String] = Array.empty[String]) = {
    val queries = vocab.isEmpty match {
      case true => predictions
      case false => predictions.filter { case (k, v) => vocab.contains(k) }
    }
    val Q = queries.length  // num of queries
    val docsPerQuery = queries(0)._2.size
    val meanAP = queries.map { case (q, candidates) => {
      val foundAt = candidates.map(_._1).indexOf(q)
      var precisionSum = 0.0
      if (foundAt != -1) {
        for (numRetrievedItems <- foundAt+1 to docsPerQuery) precisionSum += 1.0 / numRetrievedItems
      }
      precisionSum / docsPerQuery
    }}.sum / Q
    meanAP
  }

  def aggregateScoreAndTakeTop(predictions: Seq[(String, Array[(String, (Double, Double))])], wc: Double, top: Int) = {
    val aggregated = predictions.toSeq.map { case (goldMethodName, topK) => {
      val aggregatedList = topK.map { case (api, scores) => (api, wc*scores._1 + (1-wc)*scores._2)}
      (goldMethodName, aggregatedList.sortWith(_._2 > _._2).take(top))
    }}
    aggregated
  }
}
