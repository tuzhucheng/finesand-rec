package finesand

import finesand.model.PredictionPoint

object Consts {
  type PredictionPointKey = (String, Int)
  type PredictionPointMapType = collection.mutable.Map[PredictionPointKey, PredictionPoint]
  type ChangeContextMap = collection.Map[(String, String, String),collection.mutable.Set[Int]]
  type CodeContextMap = collection.Map[String,collection.mutable.Set[Int]]
  val Train = "train"
  val Test = "test"
}
