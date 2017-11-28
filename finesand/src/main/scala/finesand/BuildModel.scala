package finesand

import java.io.{File,FileInputStream,FileOutputStream,ObjectInputStream,ObjectOutputStream}

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{DataTypes, StructField, StructType}
import org.rogach.scallop._

import finesand.model.{Commit,PredictionPoint,Transaction}

object BuildModel {
  type PredictionPointKey = (String, Int)
  type PredictionPointMapType = collection.mutable.Map[PredictionPointKey, PredictionPoint]

  type IndexMutableMap = collection.mutable.Map[(String, Int), List[(Int, Int)]]

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

  def getChangeContextIndex(spark: SparkSession, corpusPath: String) = {
    val changeContextRawRDD = spark.read
      .option("header", "false")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      .schema(schema)
      .csv(s"${corpusPath}/change_context_*.txt")
      .rdd

    val changeContextRDD = changeContextRawRDD.map(r => ((r.getAs[String](0), r.getAs[String](1), r.getAs[String](2)), collection.mutable.Map(((r.getAs[String](3), r.getAs[Int](4)) -> List((r.getAs[Int](5), r.getAs[Int](6)))))))
      .reduceByKey((a, b) => plus(a, b))
    changeContextRDD.collectAsMap
  }

  def getCodeContextIndex(spark: SparkSession, corpusPath: String) = {
    val codeContextRawRDD = spark.read
      .option("header", "false")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      .schema(schema)
      .csv(s"${corpusPath}/code_context_*.txt")
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

  def getPredictionPoints(repoCorpus: String) = {
    val serializedPredictionFiles = new File(repoCorpus).listFiles.filter(f => f.getName contains "predictionPointsPart").map(f => f.getCanonicalPath)
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
    val changeContextIndex = getChangeContextIndex(spark, corpusPath)
    val codeContextIndex = getCodeContextIndex(spark, corpusPath)
    val predictionPoints = getPredictionPoints(corpusPath)

    // Dump indexes to file for debugging later
    val oos = new ObjectOutputStream(new FileOutputStream(s"$repoCorpus/changeContextIndex"))
    oos.writeObject(changeContextIndex)
    oos.close

    val oos2 = new ObjectOutputStream(new FileOutputStream(s"$repoCorpus/codeContextIndex"))
    oos2.writeObject(codeContextIndex)
    oos2.close

    // To read, see https://alvinalexander.com/scala/how-to-use-serialization-in-scala-serializable-trait
  }
}
