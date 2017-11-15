package finesand.model

class Commit(val commitId: String, val parent: Option[String], val filesChanged: List[String]) {

}
