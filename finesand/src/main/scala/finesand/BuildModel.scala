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
    val partFilePattern = if (dataset == Consts.Train) "change_context_part_*.txt" else "change_context_test_part_*.txt"
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

    val transactions = changeContextRDD.map(t => t._2)
      .reduce((a,b) => a.union(b))
      .toList

    val transactionsToId = transactions.zipWithIndex.map { case (t, i) => t -> i }.toMap
    val broadcast = spark.sparkContext.broadcast(transactionsToId)
    val broadcastedMap = broadcast.value

    val changeContextIndex = changeContextRDD.map { case (k, s) => {
      val intIds = s.map(t => broadcastedMap(t))
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
    val partFilePattern = if (dataset == Consts.Train) "code_context_part_*.txt" else "code_context_test_part*.txt"
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

    val transactions = codeContextRDD.map(t => t._2)
      .reduce((a, b) => a.union(b))
      .toList

    val transactionsToId = transactions.zipWithIndex.map { case (t, i) => t -> i }.toMap
    val broadcast = spark.sparkContext.broadcast(transactionsToId)
    val broadcastedMap = broadcast.value

    codeContextRDD.map { case (k, s) => {
      val intIds = s.map(t => broadcastedMap(t))
      (k, intIds)
    }}.collectAsMap
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
    val (trainChangeContextIndex, trainVocab) = getChangeContextIndexAndVocab(spark, repoCorpus, Consts.Train)
    val (testChangeContextIndex, testVocab) = getChangeContextIndexAndVocab(spark, repoCorpus, Consts.Test)
    var t1 = System.nanoTime
    println(s"Finished getting change context index and vocab. Took ${(t1 - t0) / 1000000000} seconds.")

    println("Getting code context index and vocab...")
    t0 = System.nanoTime
    val trainCodeContextIndex = getCodeContextIndex(spark, repoCorpus, Consts.Train)
    val testCodeContextIndex = getCodeContextIndex(spark, repoCorpus, Consts.Test)
    t1 = System.nanoTime
    println(s"Finished getting code context index and vocab. Took ${(t1 - t0) / 1000000000} seconds.")

    println("Getting prediction points...")
    t0 = System.nanoTime
    var predictionMethodSpace = collection.mutable.Set.empty[String]
    if (conf.jdk()) {
      val methods = Source.fromFile("../jdk-method-miner/jdk8_methods_filtered.txt").getLines.toSet
      predictionMethodSpace = collection.mutable.Set[String](methods.toSeq: _*)
    }
    val trainPredictionPoints = ModelUtils.getPredictionPoints(repoCorpus, Consts.Train, predictionMethodSpace)
    val testPredictionPoints = ModelUtils.getPredictionPoints(repoCorpus, Consts.Test, predictionMethodSpace)
    t1 = System.nanoTime
    println(s"Finished getting prediction points. Took ${(t1 - t0) / 1000000000} seconds.")

    println("Getting training predictions...")
    val logFileName = if (conf.jdk()) s"${repoName}-jdk" else s"${repoName}"
    val writer = new BufferedWriter(new FileWriter(s"results/${repoName}.log"))
    val totalTrainMsg = s"Total train predictions: ${trainPredictionPoints.size}"
    println(totalTrainMsg)
    writer.write(totalTrainMsg + "\n")
    val trainPredictions = ModelUtils.getPredictionsSeparateScore(trainPredictionPoints, trainVocab, trainChangeContextIndex, trainCodeContextIndex)
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
    val testPredictions = ModelUtils.getPredictionsAggregateScore(testPredictionPoints, trainVocab, trainChangeContextIndex, trainCodeContextIndex, wcMax)
    val testAccuracyMap = ModelUtils.getAccuracy(testPredictions, trainVocab)
    testAccuracyMap.foreach { case (k, m) => {
      val accuracyLine = s"Test top-$k: oov ${m("oov")}, in ${m("in")}"
      println(accuracyLine)
      writer.write(accuracyLine + "\n")
    }}

    writer.close
  }
}
