package finesand.model

class Commit(val commitId: String, val parent: Option[String], val transactions: List[Transaction]) {

}

