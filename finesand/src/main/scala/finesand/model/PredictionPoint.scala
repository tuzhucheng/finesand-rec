package finesand.model

import scala.collection.mutable.StringBuilder

import java.io.Serializable


@SerialVersionUID(100L)
class PredictionPoint(val commitId: String, val transactionIdx: Int, val variableName: String, val methodName: String, val pos: Int, val parentPos: Int) extends Serializable {
  type ChangeContextKey = (String, String, String) // operation kind, node type, label
  type CodeContextKey = (String, String) // node type, label
  type ChangeContextScoreComponent = (ChangeContextKey, Double, Double, Int) // change context key, scope weight, dep weight, position
  type CodeContextScoreComponent = (CodeContextKey, Double, Double, Int) // code context key, scope weight, dep weight, position

  val key = (commitId, transactionIdx)
  var changeContext: Option[List[ChangeContextScoreComponent]] = None
  var codeContext: Option[List[CodeContextScoreComponent]] = None

  override def toString(): String = {
    val sb = new StringBuilder()
    sb ++= s"PredictionPoint($key) ${variableName}.${methodName} at ${pos}\n"
    for (change <- changeContext.getOrElse(List())) {
      sb ++= s"$change\n"
    }
    for (code <- codeContext.getOrElse(List())) {
      sb ++= s"$code\n"
    }
    sb.toString
  }
}
