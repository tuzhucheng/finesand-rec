package finesand

import finesand.model.PredictionPoint

import org.apache.spark.sql.types.{DataTypes, StructField, StructType}

object Consts {
  type PredictionPointKey = (String, Int)
  type PredictionPointMapType = collection.mutable.Map[PredictionPointKey, PredictionPoint]
  type ChangeContextMap = collection.Map[(String, String, String),collection.mutable.Set[Int]]
  type CodeContextMap = collection.Map[String,collection.mutable.Set[Int]]
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

  val schemaCodeContext = StructType(Array(
    StructField("node_type", DataTypes.StringType),
    StructField("label", DataTypes.StringType),
    StructField("commit_id", DataTypes.StringType),
    StructField("transaction_idx", DataTypes.IntegerType),
    StructField("position", DataTypes.IntegerType),
    StructField("parentMethodPos", DataTypes.IntegerType)
  ))
}
