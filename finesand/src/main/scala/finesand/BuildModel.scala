package finesand

import java.io.{BufferedWriter,File,FileInputStream,FileOutputStream,FileWriter,ObjectInputStream,ObjectOutputStream}
import scala.io.Source

import com.typesafe.scalalogging.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{DataTypes, StructField, StructType}
import org.rogach.scallop._
import org.slf4j.LoggerFactory

import finesand.model.{Commit,PredictionPoint,Transaction}

object BuildModel {

  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val repo = opt[String]() // "../data/community-corpus/log4j"
    val jdk = opt[Boolean](default = Some(false))
    val aggregate = opt[Boolean](default = Some(false))
    verify()
  }

  type PredictionPointKey = (String, Int)
  type PredictionPointMapType = collection.mutable.Map[PredictionPointKey, PredictionPoint]
  type ChangeContextMap = collection.Map[(String, String, String),collection.mutable.Set[Int]]
  type CodeContextMap = collection.Map[String,collection.mutable.Set[Int]]
  val Train = "train"
  val Test = "test"
  val emptySet = collection.mutable.Set.empty[Int]

  val schema = StructType(Array(
    StructField("change_type", DataTypes.StringType),
    StructField("node_type", DataTypes.StringType),
    StructField("label", DataTypes.StringType),
    StructField("commit_id", DataTypes.StringType),
    StructField("transaction_idx", DataTypes.IntegerType),
    StructField("position", DataTypes.IntegerType),
    StructField("parentMethodPos", DataTypes.IntegerType)
  ))

  val schemaCodeContext = StructType(Array(
    StructField("node_type", DataTypes.StringType),
    StructField("label", DataTypes.StringType),
    StructField("commit_id", DataTypes.StringType),
    StructField("transaction_idx", DataTypes.IntegerType),
    StructField("position", DataTypes.IntegerType),
    StructField("parentMethodPos", DataTypes.IntegerType)
  ))

  def getChangeContextIndexAndVocab(spark: SparkSession, corpusPath: String, dataset: String) = {
    val partFilePattern = if (dataset == Train) "change_context_part_*.txt" else "change_context_test_part_*.txt"
    val changeContextRawRDD = spark.read
      .option("header", "false")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      .schema(schema)
      .csv(s"${corpusPath}/${partFilePattern}")
      .rdd

    val changeContextRDD = changeContextRawRDD.map(r => (
        (r.getAs[String]("change_type"), r.getAs[String]("node_type"), r.getAs[String]("label")),
        (r.getAs[String]("commit_id"), r.getAs[Int]("transaction_idx"))
      ))
      .aggregateByKey(collection.mutable.Set.empty[(String,Int)])((s, e) => s + e, (s1, s2) => s1.union(s2))

    val transactions = changeContextRDD.mapValues(v => v)
      .reduce(_ + _)
      .collect
      .toList

    val transactionsToId = transactions.zipWithIndex.map { case (t, i) => t -> i }
    val broadcast = sc.sparkContext.broadcast(transactionsToId)
    val broadcastedMap = broadcast.get

    val changeContextIndex = changeContextRDD.map { case (k, s) => {
      val intIds = s.map(t => broadcastedMap.get(t))
      (k, intIds)
    }}.collectAsMap

    val vocabRDD = changeContextRawRDD.map(r => (
        r.getAs[String]("change_type"), r.getAs[String]("node_type"), r.getAs[String]("label"))
      )
      .filter(t => t._1 == "INS" && t._2 == "MethodInvocation")
      .map(t => t._3)
      .distinct

    val vocab = vocabRDD.collect
    val pattern = "[a-zA-Z0-9_]*".r.pattern
    val validVocab = vocab.filter{x => try {
      pattern.matcher(x).matches
    } catch {
      case foo: NullPointerException => false }
    }

    (changeContextIndex, validVocab)
  }

  def getCodeContextIndex(spark: SparkSession, corpusPath: String, dataset: String) = {
    val partFilePattern = if (dataset == Train) "code_context_part_*.txt" else "code_context_test_part*.txt"
    val codeContextRawRDD = spark.read
      .option("header", "false")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      .schema(schemaCodeContext)
      .csv(s"${corpusPath}/${partFilePattern}")
      .rdd

    val codeContextRDD = codeContextRawRDD.map(r => (
        r.getAs[String]("label"),
        (r.getAs[String]("commit_id"), r.getAs[Int]("transaction_idx"))
      ))
      .aggregateByKey(collection.mutable.Set.empty[(String,Int)])((s, e) => s + e, (s1, s2) => s1.union(s2))

    val transactions = codeContextRDD.mapValues(v => v)
      .reduce(_ + _)
      .collect
      .toList

    val transactionsToId = transactions.zipWithIndex.map { case (t, i) => t -> i }
    val broadcast = sc.sparkContext.broadcast(transactionsToId)
    val broadcastedMap = broadcast.get

    codeContextRDD.map { case (k, s) => {
      val intIds = s.map(t => broadcastedMap.get(t))
      (k, intIds)
    }}.collectAsMap
  }

  def getPredictionPoints(repoCorpus: String, dataset: String, methodSpace: collection.mutable.Set[String] = collection.mutable.Set.empty[String]): PredictionPointMapType = {
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
      var currentMap = ois.readObject.asInstanceOf[PredictionPointMapType]
      if (!methodSpace.isEmpty) {
        currentMap = currentMap.filter { case (trans, pp) => methodSpace.contains(pp.methodName) }
      }
      predictionPoints ++= currentMap
      ois.close
    })
    predictionPoints
  }

  def getChangeContextScore(pp: PredictionPoint, candidate: String, changeContextIndex: ChangeContextMap, window: Int = 15) : Double = {
    val scoreComps: List[PredictionPoint#ChangeContextScoreComponent] = pp.changeContext.getOrElse(List()).sortWith(_._4 > _._4).take(window)
    val candTransactions = changeContextIndex.getOrElse(("INS", "MethodInvocation", candidate), emptySet)
    val score = scoreComps.zipWithIndex.map { case(c, i) => {
      val (wScopeCi, wDepCi) = (c._2, c._3)
      val transactions = changeContextIndex.getOrElse(c._1, emptySet)
      val nCi = transactions.size
      // co-occurrence transactions must be in same transaction and atomic change must come before prediction point
      val cooccurTransactions = transactions.intersect(candTransactions)
      val nCCi = cooccurTransactions.size
      val dCCi = i+1
      val term = (wScopeCi * wDepCi / dCCi) * Math.log((nCCi + 1) / (nCi + 1))
      Math.exp(term)
    }}.sum
    score
  }

  def getCodeContextScore(pp: PredictionPoint, candidate: String, codeContextIndex: CodeContextMap, window: Int = 15) : Double = {
    val scoreComps: List[PredictionPoint#CodeContextScoreComponent] = pp.codeContext.getOrElse(List()).sortWith(_._4 > _._4).take(window)
    val candTransactions = codeContextIndex.getOrElse(candidate, emptySet)
    val score = scoreComps.zipWithIndex.map { case(c, i) => {
      val (wScopeTi, wDepTi) = (c._2, c._3)
      val transactions = codeContextIndex.getOrElse(c._1._2, candTransactions)
      val nTi = transactions.size
      val cooccurTransactions = transactions.intersect(candTransactions)
      val nCTi = cooccurTransactions.size
      val dCTi = i+1
      val term = (wScopeTi * wDepTi / dCTi) * Math.log((nCTi + 1) / (nTi + 1))
      Math.exp(term)
    }}.sum
    score
  }

  def getPredictionsAggregateScore(predictionPoints: PredictionPointMapType, vocab: Array[String], changeContextIndex: ChangeContextMap, codeContextIndex: CodeContextMap, wc: Double, k: Int = 10) = {
    val predictions = predictionPoints.toList.par.map { case (_, pp) => {
      val changeContextScores = vocab.map(api => getChangeContextScore(pp, api, changeContextIndex))
      val codeContextScores = vocab.map(api => getCodeContextScore(pp, api, codeContextIndex))
      val scores = (changeContextScores zip codeContextScores).map { case (scoreC, scoreT) => wc*scoreC + (1-wc)*scoreT }
      val topK = (vocab zip scores).sortWith(_._2 > _._2).take(k)
      (pp.methodName, topK)
    }}
    predictions.seq
  }

  def getPredictionsSeparateScore(predictionPoints: PredictionPointMapType, vocab: Array[String], changeContextIndex: ChangeContextMap, codeContextIndex: CodeContextMap, k: Option[Int] = None) = {
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

  def findOptimalWc(trainPredictions: scala.collection.mutable.Map[String, Array[(String, (Double, Double))]]) = {
    // TODO
    0.5
  }

  def main(args: Array[String]): Unit = {
    val logger = Logger("BuildModel")
    val conf = new Conf(args)
    val aggregateCounts = conf.aggregate()
    val repo = conf.repo().stripSuffix("/")
    val repoName = repo.split("/").last
    val repoCorpus = s"${repo}-counts"
    val warehouseLocation = "file:${system:user.dir}/spark-warehouse"
    val spark = SparkSession
      .builder()
      .appName("finesand")
      .config("spark.sql.warehouse.dir", warehouseLocation)
      .getOrCreate()

    import spark.implicits._

    // Using println() instead of logging to distinguish from Spark logging
    println("Getting change context index and vocab...")
    var t0 = System.nanoTime
    val (trainChangeContextIndex, trainVocab) = getChangeContextIndexAndVocab(spark, repoCorpus, Train)
    val (testChangeContextIndex, testVocab) = getChangeContextIndexAndVocab(spark, repoCorpus, Test)
    var t1 = System.nanoTime
    println(s"Finished getting change context index and vocab. Took ${(t1 - t0) / 1000000000} seconds.")

    println("Getting code context index and vocab...")
    t0 = System.nanoTime
    val trainCodeContextIndex = getCodeContextIndex(spark, repoCorpus, Train)
    val testCodeContextIndex = getCodeContextIndex(spark, repoCorpus, Test)
    t1 = System.nanoTime
    println(s"Finished getting code context index and vocab. Took ${(t1 - t0) / 1000000000} seconds.")

    println("Getting prediction points...")
    t0 = System.nanoTime
    var predictionMethodSpace = collection.mutable.Set.empty[String]
    if (conf.jdk()) {
      val methods = Source.fromFile("../jdk-method-miner/jdk8_methods_filtered.txt").getLines.toSet
      predictionMethodSpace = collection.mutable.Set[String](methods.toSeq: _*)
    }
    val trainPredictionPoints = getPredictionPoints(repoCorpus, Train, predictionMethodSpace)
    val testPredictionPoints = getPredictionPoints(repoCorpus, Test, predictionMethodSpace)
    t1 = System.nanoTime
    println(s"Finished getting prediction points. Took ${(t1 - t0) / 1000000000} seconds.")

    println("Getting training predictions...")
    val logFileName = if (conf.jdk()) s"${repoName}-jdk" else s"${repoName}"
    val writer = new BufferedWriter(new FileWriter(s"results/${repoName}.log"))
    val totalTrainMsg = s"Total train predictions: ${trainPredictionPoints.size}"
    println(totalTrainMsg)
    writer.write(totalTrainMsg + "\n")
    val trainPredictions = getPredictionsSeparateScore(trainPredictionPoints, trainVocab, trainChangeContextIndex, trainCodeContextIndex)
    var maxMAP = 0.0
    var wcMax = 0.0
    for (i <- 0 to 100 by 10) {
      t0 = System.nanoTime
      val wc = i.toDouble / 100
      val aggregatedScorePredictions = ModelUtils.aggregateScoreAndTakeTop(trainPredictions, wc, 10)
      val wcMAP = ModelUtils.getMAP(aggregatedScorePredictions)
      if (wcMAP > maxMAP) {
        maxMAP = wcMAP
        wcMax = wc
        val accuracyMap = ModelUtils.getAccuracy(aggregatedScorePredictions, trainVocab)
        val header = s"wc: $wc, MAP: ${wcMAP}\n====================="
        println(header)
        writer.write(header + "\n")
        accuracyMap.foreach { case (k, m) => {
          val accuracyLine = s"top-$k: oov ${m("oov")}, in ${m("in")}"
          println(accuracyLine)
          writer.write(accuracyLine + "\n")
        }}
      }
      t1 = System.nanoTime
      val timingMsg = s"Parameter search for wc=$wc took ${(t1 - t0) / 1000000000} seconds..."
      println(timingMsg)
      writer.write(timingMsg + "\n")
    }

    val totalTestMsg = s"Total test predictions: ${testPredictionPoints.size}"
    println(totalTestMsg)
    writer.write(totalTestMsg + "\n")
    val bestWcMsg = s"Best wc=$wcMax MAP=$maxMAP"
    println(bestWcMsg)
    writer.write(bestWcMsg + "\n")
    val testPredictions = getPredictionsAggregateScore(testPredictionPoints, trainVocab, trainChangeContextIndex, trainCodeContextIndex, wcMax)
    val testAccuracyMap = ModelUtils.getAccuracy(testPredictions, trainVocab)
    testAccuracyMap.foreach { case (k, m) => {
      val accuracyLine = s"Test top-$k: oov ${m("oov")}, in ${m("in")}"
      println(accuracyLine)
      writer.write(accuracyLine + "\n")
    }}

    writer.close
  }
}
