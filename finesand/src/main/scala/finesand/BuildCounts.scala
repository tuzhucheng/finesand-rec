package finesand

import scala.collection.JavaConversions._
import scala.io.Source
import sys.process._
import java.io.{BufferedWriter,File,FileWriter}
import java.util.concurrent.atomic.AtomicInteger

import com.github.gumtreediff.actions.ActionGenerator
import com.github.gumtreediff.actions.model.Action
import com.github.gumtreediff.client.Run
import com.github.gumtreediff.gen.Generators
import com.github.gumtreediff.io.TreeIoUtils
import com.github.gumtreediff.matchers.Matcher
import com.github.gumtreediff.matchers.Matchers
import com.github.gumtreediff.tree.TreeContext

import finesand.model.{Commit,Transaction}

object BuildCounts {
    def getCommits(corpusDir: String): List[Commit] = {
        val dir = new File(corpusDir)
        if (!dir.exists || !dir.isDirectory)
            List[Commit]()

        val commitDirs: List[File] = dir.listFiles.toList.filter(f => f.isDirectory)
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

    def generateChangeContext(commits: List[Commit], repoCorpus: String): Unit = {
        Run.initGenerators()
        val completed = new AtomicInteger()

        val total = commits.flatMap(c => {
          c.transactions
           .filter(t => t.path.endsWith(".java"))
        }).size

        val changeContextIndex = commits.flatMap(c => {
          c.transactions
           .filter(t => t.path.endsWith(".java"))
           .zipWithIndex.flatMap{ case (t, i) => {
                val nameParts = t.path.split("/")
                val oldFilePath = (nameParts.init :+ ("old_" + nameParts.last)).mkString("/")
                val file1 = s"${repoCorpus}/${c.commitId}/${oldFilePath}"
                val file2 = s"${repoCorpus}/${c.commitId}/${t.path}"
                val srcTree = Generators.getInstance().getTree(file1)
                val dstTree = Generators.getInstance().getTree(file2)
                val src = srcTree.getRoot
                val dst = dstTree.getRoot
                val matcher = Matchers.getInstance().getMatcher(src, dst)
                // Use `match` since match is a keyword in Scala
                matcher.`match`
                val generator = new ActionGenerator(src, dst, matcher.getMappings)
                generator.generate
                val actions = generator.getActions.toList.map(a => {
                    val node = a.getNode
                    val operationKind = a.getName
                    val nodeType = dstTree.getTypeLabel(node)
                    val label = nodeType match {
                      case "MethodInvocation" => if (node.getChildren.length > 1) node.getChild(1).getLabel else node.getLabel
                      case "SimpleType" | "SimpleName" | "BooleanLiteral" | "NullLiteral" => node.getLabel
                      case _ => ""
                    }

                    // position refers to the index of character in the file that contains the action node
                    val position = node.getPos
                    val change = (operationKind, nodeType, label)
                    val changeLoc = (c.commitId, i, position)
                    (change, changeLoc)
                })

                val done = completed.incrementAndGet()
                println(s"Processed ${done} / ${total} transactions")

                actions
            }}
        })

        val changeContextFile = s"${repoCorpus}/change_context.txt"
        val writer = new BufferedWriter(new FileWriter(changeContextFile))
        changeContextIndex.foreach(c => {
          writer.write(s"${c._1._1},${c._1._2},${c._1._3},${c._2._1},${c._2._2},${c._2._3}\n")
        })
        writer.close
    }

    def main(args: Array[String]): Unit = {
        val repo = "../data/community-corpus/log4j"
        val repoCorpus = s"${repo}-corpus"
        val commits = getCommits(repoCorpus)
        generateChangeContext(commits, repoCorpus)
    }
}
