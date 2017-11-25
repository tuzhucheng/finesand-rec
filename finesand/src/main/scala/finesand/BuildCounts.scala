package finesand

import scala.io.Source
import sys.process._
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import finesand.model.{Commit,Transaction}

object BuildCounts {
    def getCommits(corpusDir: String): List[Commit] = {
        val dir = new File(corpusDir)
        if (!dir.exists || !dir.isDirectory)
            List[Commit]()

        val commitDirs: List[File] = dir.listFiles.toList
        val commits = commitDirs.map(d => {
            val commitHash = d.getName
            val transactionsFile = new File(d, "finesand_transactions.txt")
            val bufferedSource = Source.fromFile(transactionsFile)
            val transactions = bufferedSource.getLines.map(line => {
                val Array(path, changeType, oldBlobId, newBlobId) = line.split(",")
                new Transaction(path, changeType, oldBlobId, newBlobId)
            })
            new Commit(commitHash, None, transactions.toList)
        })
        commits
    }

    def main(args: Array[String]): Unit = {
        val repoCorpus = "../data/community-corpus/log4j-corpus"
        val commits = getCommits(repoCorpus)
    }
}
