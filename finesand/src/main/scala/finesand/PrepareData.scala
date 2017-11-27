package finesand

import sys.process._
import java.io.{BufferedWriter,File,FileWriter}
import java.util.concurrent.atomic.AtomicInteger

import finesand.model.{Commit,Transaction}

object PrepareData {
  def main(args: Array[String]): Unit = {
    //val conf = new Conf(args)
    //val repo = conf.repo()
    val repo = args(0)
    val projectDir = new File(repo)
    val projectName = repo.split("/").last
    val reposDir = projectDir.getParentFile()

    // Split commits into training and testing set
    val (trainCommits, testCommits) = TrainTestSplit.split(repo, 0.9)
    val trainCommitsMap = trainCommits.map(c => c.commitId -> c).toMap

    // Build corpus directory, which contains directories of commits
    Process(s"mkdir -p ${projectName}-corpus", reposDir)!!
    val corpusPath = s"${repo}-corpus"
    val corpusDir = new File(corpusPath)
    val completed = new AtomicInteger()
    trainCommitsMap foreach {
      case (commitId, commit) => {
        Process(s"mkdir -p ${commitId}", corpusDir).!!
        val commitPath = s"${corpusPath}/${commitId}"
        val commitDir = new File(commitPath)
        commit.transactions foreach (transaction => {
          val fileParentPath = transaction.path.split("/").init.mkString("/")
          // If file is stored at repo root, fileParentPath will be empty
          if (!fileParentPath.isEmpty)
            Process(s"mkdir -p ${fileParentPath}", commitDir).!!
          val file = new File(s"${commitDir}/${transaction.path}")

        val nameParts = transaction.path.split("/")
        val oldFilePath = (nameParts.init :+ ("old_" + nameParts.last)).mkString("/")
        val oldFile = new File(s"${commitDir}/${oldFilePath}")
        (Process(s"git show ${transaction.newBlobId}", projectDir) #> file).!
        (Process(s"git show ${transaction.oldBlobId}", projectDir) #> oldFile).!
        })

        val changeFile = new File(s"${commitDir}/finesand_transactions.txt")
        val bw = new BufferedWriter(new FileWriter(changeFile))
        commit.transactions foreach (transaction => bw.write(transaction + "\n"))
        bw.close()
      }
      val done = completed.incrementAndGet()
      if (done % 100 == 0) {
        println(s"Processing commit changed files, ${done} / ${trainCommitsMap.size} commits")
      }
    }
  }
}
