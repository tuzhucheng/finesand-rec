package finesand

import sys.process._
import java.io.File

import finesand.model.{Commit,Transaction}

object TrainTestSplit {
  def split(path: String, ratio: Double, branch: String = "trunk") : (Seq[Commit], Seq[Commit]) = {
    val projectDir = new File(path)
    // newest commits at bottom when reversed.
    val commitIds = Process(s"git rev-list --date-order $branch --reverse --abbrev-commit", projectDir).!!.split("\n")
    val commits = commitIds.toIterator.zipWithIndex.map { case (cid, idx) => {
      if (idx % 1000 == 0) {
        println(s"Processing ${idx+1} / ${commitIds.length} commits")
      }
      val filesChanged = Process(s"git diff-tree --no-commit-id -r $cid", projectDir).!!.split("\n").toList
      val modifiedFiles = filesChanged.map(s => {
        try {
          val Array(_, _, oldBlob, newBlob, changeType, path) = s.split("\\s")
          new Transaction(path, changeType, oldBlob, newBlob)
        } catch {
          case foo: MatchError => new Transaction("", "", "", "")
        }
      }).filter(t => t.changeType == "M")
      val parent = if (idx != 0) Some(commitIds(idx-1)) else None
      new Commit(cid, parent, modifiedFiles)
    } }

    val (trainPart, testPart) = commits.zipWithIndex.partition{ case (c, i) => i < Math.floor(commitIds.length * ratio) }
    val train = trainPart.map(t => t._1).toSeq
    val test = testPart.map(t => t._1).toSeq
    println(s"$path has ${commitIds.length} commits, and ${train.length} are used for training, ${test.length} are used for testing.")
    (train, test)
  }
}
