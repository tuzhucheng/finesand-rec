package finesand.model

class Transaction(val path: String, val changeType: String, val oldBlobId: String, val newBlobId: String) {
    override def toString(): String = s"$path,$changeType,$oldBlobId,$newBlobId"
}

class Commit(val commitId: String, val parent: Option[String], val transactions: List[Transaction]) {

}
