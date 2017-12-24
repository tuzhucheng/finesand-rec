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
    val community = opt[Boolean]()
    val project = opt[Boolean]()
    val repo = opt[String]() // "../data/community-corpus/log4j"
    val jdk = opt[Boolean](default = Some(false))
    requireOne(community, project)
    verify()
  }

  def getChangeContextIndexAndVocab(spark: SparkSession, paths: Seq[String]) = {
    val changeContextRawRDD = spark.read
      .option("header", "false")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      .schema(Consts.schema)
      .csv(paths:_*)
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

  def getCodeContextIndex(spark: SparkSession, paths: Seq[String]) = {
    val codeContextRawRDD = spark.read
      .option("header", "false")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      .schema(Consts.schemaCodeContext)
      .csv(paths:_*)
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
    val repo = conf.repo().stripSuffix("/")
    val repoName = repo.split("/").last
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
    val (trainChangeContextIndex, trainVocab) = conf.project.isSupplied match {
      case true => {
        val paths = Seq(s"${repo}-counts/change_context_part_*.txt")
        getChangeContextIndexAndVocab(spark, paths)
      }
      case false => {
        // community edition
        val subdirs = Utils.getListOfSubDirectories(repo)
        val paths = subdirs.map(dirname => s"${repo}/${dirname}/change_context_part_*.txt")
        getChangeContextIndexAndVocab(spark, paths)
      }
    }
    var t1 = System.nanoTime
    println(s"Finished getting change context index and vocab. Took ${(t1 - t0) / 1000000000} seconds.")

    println("Getting code context index and vocab...")
    t0 = System.nanoTime
    val trainCodeContextIndex = conf.project.isSupplied match {
      case true => {
        val paths = Seq(s"${repo}-counts/code_context_part_*.txt")
        getCodeContextIndex(spark, paths)
      }
      case false => {
        // community edition
        val subdirs = Utils.getListOfSubDirectories(repo)
        val paths = subdirs.map(dirname => s"${repo}/${dirname}/change_context_part_*.txt")
        getCodeContextIndex(spark, paths)
      }
    }
    t1 = System.nanoTime
    println(s"Finished getting code context index and vocab. Took ${(t1 - t0) / 1000000000} seconds.")

    println("Getting training prediction points...")
    t0 = System.nanoTime
    var predictionMethodSpace = collection.mutable.Set.empty[String]
    if (conf.jdk()) {
      val methods = Source.fromFile("../jdk-method-miner/jdk8_methods_filtered.txt").getLines.toSet
      predictionMethodSpace = collection.mutable.Set[String](methods.toSeq: _*)
    }

    val predictionPointPaths = conf.project.isSupplied match {
      case true => Seq(s"${repo}-counts")
      case false => {
        val subdirs = Utils.getListOfSubDirectories(repo)
        subdirs.map(dirname => s"${repo}/${dirname}")
      }
    }

    val trainPredictionPoints = ModelUtils.getPredictionPoints(predictionPointPaths, Consts.Train, predictionMethodSpace)
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

    val bestWcMsg = s"Best wc=$wcMax MAP=$maxMAP"
    println(bestWcMsg)
    writer.write(bestWcMsg + "\n")

    val testPredictionPoints = conf.project.isSupplied match {
      case true => collection.mutable.Map(repoName -> ModelUtils.getPredictionPoints(predictionPointPaths, Consts.Test, predictionMethodSpace))
      case false => ModelUtils.getPredictionPointsSeparately(predictionPointPaths, Consts.Test, predictionMethodSpace)
    }

    testPredictionPoints.foreach { case (name, predictionPoints) => {
      val testRepoHeaderMsg = s"Test repo: $name\n==============================="
      println(testRepoHeaderMsg)
      writer.write(testRepoHeaderMsg + "\n")

      val totalTestMsg = s"Total test predictions: ${predictionPoints.size}"
      println(totalTestMsg)
      writer.write(totalTestMsg + "\n")
      val testPredictions = ModelUtils.getPredictionsAggregateScore(predictionPoints, trainVocab, trainChangeContextIndex, trainCodeContextIndex, wcMax)
      val testAccuracyMap = ModelUtils.getAccuracy(testPredictions, trainVocab)
      testAccuracyMap.foreach { case (k, m) => {
        val accuracyLine = s"Test top-$k: oov ${m("oov")}, in ${m("in")}"
        println(accuracyLine)
        writer.write(accuracyLine + "\n")
      }}
    }}

    writer.close
  }
}
