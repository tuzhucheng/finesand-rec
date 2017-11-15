package finesand

import sys.process._
import java.io.File

import finesand.model.Commit

object ChangeContextCorpusBuilderDriver {
    def main(args: Array[String]): Unit = {
        val repo = "../data/community-corpus/log4j"
        val projectDir = new File(repo)
        val projectName = repo.split("/").last
        val reposDir = projectDir.getParentFile()

        val (trainCommits, testCommits) = TrainTestSplit.split(repo, 0.9)
        val trainCommitsMap = trainCommits.map(c => c.commitId -> c).toMap

        Process(s"mkdir -p ${projectName}-corpus", reposDir)!!
        val corpusPath = s"${repo}-corpus"
        val corpusDir = new File(corpusPath)
        trainCommitsMap.zipWithIndex foreach {
            case ((commitId, commit), idx) => {
                if (idx % 100 == 0) {
                    println(s"Processing commit changed files, ${idx+1} / ${trainCommitsMap.size} commits")
                }
                Process(s"mkdir -p ${commitId}", corpusDir).!!
                val commitPath = s"${corpusPath}/${commitId}"
                val commitDir = new File(commitPath)
                commit.filesChanged foreach (filePath => {
                    val fileParentPath = filePath.split("/").init.mkString("/")
                    if (!fileParentPath.isEmpty)
                        Process(s"mkdir -p ${fileParentPath}", commitDir).!!
                    val file = new File(s"${commitDir}/${filePath}")
                    try {
                        val status = (Process(s"git show ${commitId}:$filePath", projectDir) #> file).!
                    } catch {
                        case status => println(status)
                    }
                })
            }
        }
    }
}
