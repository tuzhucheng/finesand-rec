package finesand.model

class Transaction(val path: String, val changeType: String, val oldBlobId: String, val newBlobId: String) {
  override def toString(): String = s"$path,$changeType,$oldBlobId,$newBlobId"
}
