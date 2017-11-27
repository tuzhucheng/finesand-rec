package finesand.model

import scala.collection.mutable.StringBuilder

import java.io.Serializable

class Transaction(val path: String, val changeType: String, val oldBlobId: String, val newBlobId: String) {
  override def toString(): String = s"$path,$changeType,$oldBlobId,$newBlobId"
}

class Commit(val commitId: String, val parent: Option[String], val transactions: List[Transaction]) {

}

@SerialVersionUID(100L)
class PredictionPoint(val commitId: String, val transactionIdx: Int, val variableName: String, val methodName: String) extends Serializable {
  type Change = (String, String, String) // operation kind, node type, label
  type ScoreComponent = (Change, Double, Double, Int) // change, scope weight, dep weight, position

  val key = (commitId, transactionIdx, variableName, methodName)
  var changeContext: Option[List[ScoreComponent]] = None
  var codeContext: Option[List[ScoreComponent]] = None

  override def toString(): String = {
    val sb = new StringBuilder()
    sb ++= s"PredictionPoint($key)\n"
    for (change <- changeContext.getOrElse(List())) {
      sb ++= s"$change\n"
    }
    for (code <- codeContext.getOrElse(List())) {
      sb ++= s"$code\n"
    }
    sb.toString
  }
}
