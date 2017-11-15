package finesand

import sys.process._
import java.io.File

import finesand.model.Commit

object TrainTestSplit {
    def split(path: String, ratio: Double, branch: String = "trunk") : (List[Commit], List[Commit]) = {
        val projectDir = new File(path)
        // newest commits at bottom when reversed.
        val commitIds = Process(s"git rev-list --date-order $branch --reverse", projectDir).!!.split("\n")
        val commits = commitIds.zipWithIndex.map { case (cid, idx) => {
            if (idx % 100 == 0) {
                println(s"Processing ${idx+1} / ${commitIds.length} commits")
            }
            val filesChanged = Process(s"git diff-tree --no-commit-id --name-only -r $cid",         projectDir).!!.split("\n").toList
            val parent = if (idx != 0) Some(commitIds(idx-1)) else None
            new Commit(cid, parent, filesChanged)
        } }.toList

        val (train, test) = commits.splitAt(Math.floor(commits.length * ratio).toInt)
        println(s"$path has ${commits.length} commits, and ${train.length} are used for training.")
        (train, test)
    }
}
