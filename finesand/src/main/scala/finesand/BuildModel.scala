package finesand

import java.io.{BufferedWriter,File,FileInputStream,FileOutputStream,FileWriter,ObjectInputStream,ObjectOutputStream}

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{DataTypes, StructField, StructType}
import org.rogach.scallop._

import finesand.model.{Commit,PredictionPoint,Transaction}

object BuildModel {
  type PredictionPointKey = (String, Int)
  type PredictionPointMapType = collection.mutable.Map[PredictionPointKey, PredictionPoint]
  type IndexMutableMap = collection.mutable.Map[(String, Int), List[(Int, Int)]]
  type ChangeContextMap = collection.Map[(String, String, String),IndexMutableMap]
  type CodeContextMap = collection.Map[String,IndexMutableMap]
  val Train = "train"
  val Test = "test"

  val schema = StructType(Array(
    StructField("change_type", DataTypes.StringType),
    StructField("node_type", DataTypes.StringType),
    StructField("label", DataTypes.StringType),
    StructField("commit_id", DataTypes.StringType),
    StructField("transaction_idx", DataTypes.IntegerType),
    StructField("position", DataTypes.IntegerType),
    StructField("parentMethodPos", DataTypes.IntegerType)
  ))

  def plus(m1: IndexMutableMap, m2: IndexMutableMap) = {
    m2 foreach {
      case (k, v) =>
        if (m1 contains k) {
          m1(k) ++= v
        } else {
          m1 += (k -> v)
        }
    }
    m1
  }

  def getChangeContextIndexAndVocab(spark: SparkSession, corpusPath: String, dataset: String) = {
    val partFilePattern = if (dataset == Train) "change_context_part_*.txt" else "change_context_test_part_*.txt"
    val changeContextRawRDD = spark.read
      .option("header", "false")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      .schema(schema)
      .csv(s"${corpusPath}/${partFilePattern}")
      .rdd

    val changeContextRDD = changeContextRawRDD.map(r => ((r.getAs[String](0), r.getAs[String](1), r.getAs[String](2)), collection.mutable.Map(((r.getAs[String](3), r.getAs[Int](4)) -> List((r.getAs[Int](5), r.getAs[Int](6)))))))
      .reduceByKey((a, b) => plus(a, b))

    val changeContextIndex = changeContextRDD.collectAsMap

    val vocabRDD = changeContextRawRDD.map(r => (r.getAs[String](0), r.getAs[String](1), r.getAs[String](2)))
      .filter(t => t._1 == "INS" && t._2 == "MethodInvocation")
      .map(t => t._3)
      .distinct

    val vocab = vocabRDD.collect

    (changeContextIndex, vocab)
  }

  def getCodeContextIndex(spark: SparkSession, corpusPath: String, dataset: String) = {
    val partFilePattern = if (dataset == Train) "code_context_part_*.txt" else "code_context_test_part*.txt"
    val codeContextRawRDD = spark.read
      .option("header", "false")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      .schema(schema)
      .csv(s"${corpusPath}/${partFilePattern}")
      .rdd

    val codeContextRDD = codeContextRawRDD.map(r => (r.getAs[String](2), collection.mutable.Map(((r.getAs[String](3), r.getAs[Int](4)) -> List((r.getAs[Int](5), r.getAs[Int](6)))))))
      .reduceByKey((a, b) => plus(a, b))
      .map{ case (k, m) => {
        m foreach {
          case (commit, list) => list.distinct.sorted
        }
        (k, m)
      }}
    codeContextRDD.collectAsMap
  }

  def getPredictionPoints(repoCorpus: String, dataset: String) = {
    val partFilePattern = if (dataset == Train) "predictionPointsPart" else "predictionPointsTestPart"
    val serializedPredictionFiles = new File(repoCorpus).listFiles.filter(f => f.getName contains partFilePattern).map(f => f.getCanonicalPath)
    var predictionPoints: PredictionPointMapType = collection.mutable.Map()
    serializedPredictionFiles.foreach(f => {
      val ois = new ObjectInputStream(new FileInputStream(f)) {
        override def resolveClass(desc: java.io.ObjectStreamClass): Class[_] = {
          try { Class.forName(desc.getName, false, getClass.getClassLoader) }
          catch { case ex: ClassNotFoundException => super.resolveClass(desc) }
        }
      }
      val currentMap = ois.readObject.asInstanceOf[PredictionPointMapType]
      predictionPoints ++= currentMap
      ois.close
    })
    predictionPoints
  }

  def getChangeContextScore(pp: PredictionPoint, candidate: String, changeContextIndex: ChangeContextMap, window: Int = 15) : Double = {
    val scoreComps: List[PredictionPoint#ScoreComponent] = pp.changeContext.getOrElse(List()).sortWith(_._4 > _._4).take(window)
    val candChangeContext = changeContextIndex.getOrElse(("INS", "MethodInvocation", candidate), collection.mutable.Map[(String, Int), List[(Int, Int)]]())
    val candChangeContextKeys = candChangeContext.keys.toSet
    val score = scoreComps.zipWithIndex.map { case(c, i) => {
      val (wScopeCi, wDepCi) = (c._2, c._3)
      val transactions = changeContextIndex.getOrElse(c._1, Map())
      val nCi = transactions.size
      // co-occurrence transactions must be in same transaction and atomic change must come before prediction point
      val cooccurTransactions = transactions.filter{ case (transKey, locs) => candChangeContextKeys.contains(transKey) }
      val nCCi = cooccurTransactions.size
      val dCCi = i+1
      val term = (wScopeCi * wDepCi / dCCi) * Math.log((nCCi + 1) / (nCi + 1))
      Math.exp(term)
    }}.sum
    score
  }

  def getCodeContextScore(pp: PredictionPoint, candidate: String, codeContextIndex: CodeContextMap, window: Int = 15) : Double = {
    val scoreComps: List[PredictionPoint#ScoreComponent] = pp.codeContext.getOrElse(List()).sortWith(_._4 > _._4).take(window)
    val candCodeContext = codeContextIndex.getOrElse(candidate, collection.mutable.Map[(String, Int), List[(Int, Int)]]())
    val candCodeContextKeys = candCodeContext.keys.toSet
    val score = scoreComps.zipWithIndex.map { case(c, i) => {
      val (wScopeTi, wDepTi) = (c._2, c._3)
      val transactions = codeContextIndex.getOrElse(c._1._3, collection.mutable.Map[(String, Int), List[(Int, Int)]]())
      val nTi = transactions.size
      // co-occurrence transactions must be in same transaction and token must come before prediction point token
      val cooccurTransactions = transactions.filter{ case (transKey, locs) => candCodeContextKeys.contains(transKey) }
      val nCTi = cooccurTransactions.size
      val dCTi = i+1
      val term = (wScopeTi * wDepTi / dCTi) * Math.log((nCTi + 1) / (nTi + 1))
      Math.exp(term)
    }}.sum
    score
  }

  def getPredictions(predictionPoints: PredictionPointMapType, vocab: Array[String], changeContextIndex: ChangeContextMap, codeContextIndex: CodeContextMap, wc: Double, k: Int = 5) = {
    val predictions = predictionPoints.map { case (_, pp) => {
      val changeContextScores = vocab.map(api => getChangeContextScore(pp, api, changeContextIndex))
      val codeContextScores = vocab.map(api => getCodeContextScore(pp, api, codeContextIndex))
      // Normally want to compute score + rank by commented line below. But we return top 20 so we can
      // explore optimal value of wc without recomputing this function again
      // val scores = (changeContextScores zip codeContextScores).map { case (scoreC, scoreT) => wc*scoreC + (1-wc)*scoreT }
      // val topK = (vocab zip scores).sortWith(_._2 > _._2).take(k)
      val scores = (changeContextScores zip codeContextScores)
      val topK = (vocab zip scores).sortWith((a, b) => (a._2._1 + a._2._2) > (a._2._1 + a._2._2)).take(20)
      (pp.methodName, topK)
    }}
    predictions
  }

  def aggregateScoreAndTakeTop(predictions: scala.collection.mutable.Map[String, Array[(String, (Double, Double))]], wc: Double, top: Int) = {
    val aggregated = predictions.map { case (goldMethodName, topK) => {
      val aggregatedList = topK.map { case (api, scores) => (api, wc*scores._1 + (1-wc)*scores._2)}
      goldMethodName -> aggregatedList.sortWith(_._2 > _._2).take(top)
    }}
    aggregated
  }

  implicit def bool2int(b:Boolean) = if (b) 1 else 0

  // Get top-1,2,3,4,5,10 accuracy
  def getAccuracy(predictions: scala.collection.mutable.Map[String, Array[(String, Double)]], vocab: Array[String]) = {
    val ks = List(1, 2, 3, 4, 5, 10)
    val oovAcc = predictions.map { case (goldMethodName, topK) => {
      ks.map(k => if (topK.toStream.take(k).contains(goldMethodName)) 1 else 0)
    }}
    val oovHits = oovAcc.transpose.map(l => l.reduce(_ + _))
    val oovTotal = List.fill(6)(oovAcc.size)
    val oovFinal = oovHits.zip(oovTotal).map(t => t._1 / t._2).toList

    val inAcc = predictions.filter(kv => vocab.contains(kv._1)).map { case (goldMethodName, topK) => {
      ks.map(k => if (topK.toStream.take(k).contains(goldMethodName)) 1 else 0)
    }}
    val inHits = oovAcc.transpose.map(l => l.reduce(_ + _))
    val inTotal = List.fill(6)(inHits.size)
    val inFinal = oovHits.zip(inTotal).map(t => t._1 / t._2).toList

    ks.zipWithIndex.map{ case (k, i) => k -> Map("oov" -> oovFinal(i), "in" -> inFinal(i)) }
  }

  def findOptimalWc(trainPredictions: scala.collection.mutable.Map[String, Array[(String, (Double, Double))]]) = {
    // TODO
    0.5
  }

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)
    val repo = conf.repo()
    val repoCorpus = s"${repo}-corpus"
    val warehouseLocation = "file:${system:user.dir}/spark-warehouse"
    val spark = SparkSession
      .builder()
      .appName("finesand")
      .config("spark.sql.warehouse.dir", warehouseLocation)
      .config("spark.master", "local")
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")
    import spark.implicits._

    val corpusPath = s"${repo}-corpus"

    val (trainChangeContextIndex, trainVocab) = getChangeContextIndexAndVocab(spark, corpusPath, Train)
    val trainCodeContextIndex = getCodeContextIndex(spark, corpusPath, Train)
    val trainPredictionPoints = getPredictionPoints(corpusPath, Train)
    val trainPredictions = getPredictions(trainPredictionPoints, trainVocab, trainChangeContextIndex, trainCodeContextIndex, 0.5)
    List(0, 0.25, 0.5, 0.75, 1).map( wc => {
      val trainPredictionsCombined = aggregateScoreAndTakeTop(trainPredictions, wc, 20)
      val accuracyMap = getAccuracy(trainPredictionsCombined, trainVocab)
      println(s"wc: $wc")
      accuracyMap.foreach { case (k, m) => {
        println(s"top-$k: oov ${m("oov")}, in ${m("in")}")
      }}
    })

    println(s"Total train predictions: ${trainPredictions.size}")
    val writer = new BufferedWriter(new FileWriter(s"${repoCorpus}/train_predictions.txt"))
    trainPredictions.foreach { case (goldMethodName, topK) => {
      val candidatesStr = topK.map { case (api, scores) => {
        val scoresStr = s"${scores._1},${scores._2}"
        s"${api},${scoresStr}"
      }}.mkString(",")
      writer.write(goldMethodName + "," + candidatesStr + "\n")
    }}
    writer.close

    val optimalWc = findOptimalWc(trainPredictions)

    // Dump indexes to file for debugging later
     //val oos = new ObjectOutputStream(new FileOutputStream(s"$repoCorpus/changeContextIndex"))
     //oos.writeObject(trainChangeContextIndex)
     //oos.close

     //val oos2 = new ObjectOutputStream(new FileOutputStream(s"$repoCorpus/codeContextIndex"))
     //oos2.writeObject(trainCodeContextIndex)
     //oos2.close

    // To read, see https://alvinalexander.com/scala/how-to-use-serialization-in-scala-serializable-trait
  }
}
