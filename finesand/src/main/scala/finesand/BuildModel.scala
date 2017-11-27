package finesand

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{DataTypes, StructField, StructType}
import org.rogach.scallop._

object BuildModel {

  def plus(m1: collection.mutable.Map[(String, Int), List[Int]], m2: collection.mutable.Map[(String, Int), List[Int]]) = {
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

  def main(args: Array[String]): Unit = {
    val warehouseLocation = "file:${system:user.dir}/spark-warehouse"
    val spark = SparkSession
      .builder()
      .appName("finesand")
      .config("spark.sql.warehouse.dir", warehouseLocation)
      .config("spark.master", "local")
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")
    import spark.implicits._

    val schema = StructType(Array(
      StructField("change_type", DataTypes.StringType),
      StructField("node_type", DataTypes.StringType),
      StructField("label", DataTypes.StringType),
      StructField("commit_id", DataTypes.StringType),
      StructField("transaction_idx", DataTypes.IntegerType),
      StructField("position", DataTypes.IntegerType)
    ))

    val changeContextRawRDD = spark.read
      .option("header", "false")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      .schema(schema)
      .csv("../data/community-corpus/log4j-corpus/change_context.txt")
      .rdd

    val changeContextRDD = changeContextRawRDD.map(r => ((r.getAs[String](0), r.getAs[String](1), r.getAs[String](2)), collection.mutable.Map(((r.getAs[String](3), r.getAs[Int](4)) -> List(r.getAs[Int](5))))))
      .reduceByKey((a, b) => plus(a, b))
    val changeContextIndex = changeContextRDD.collectAsMap

    val codeContextRawRDD = spark.read
      .option("header", "false")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      .schema(schema)
      .csv("../data/community-corpus/log4j-corpus/code_context.txt")
      .rdd

    val codeContextRDD = codeContextRawRDD.map(r => (r.getAs[String](2), collection.mutable.Map(((r.getAs[String](3), r.getAs[Int](4)) -> List(r.getAs[Int](5))))))
      .reduceByKey((a, b) => plus(a, b))
      .map{ case (k, m) => {
        m foreach {
          case (commit, list) => list.distinct.sorted
        }
        (k, m)
      }}
    val codeContextIndex = changeContextRDD.collectAsMap
  }
}
