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
import com.github.gumtreediff.tree.{ITree,TreeContext}

import finesand.model.{Commit,Transaction}

object BuildCounts {
  val disallowedTypes = List("CompilationUnit", "PackageDeclaration", "ImportDeclaration")

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

  def getActionsForTrees(srcTree: TreeContext, dstTree: TreeContext, commit: Commit, transactionIdx: Int) = {
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
      val changeLoc = (commit.commitId, transactionIdx, position)
      (change, changeLoc)
    })
    actions
  }

  def generateChangeContext(commits: List[Commit], repoCorpus: String): Unit = {
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
          val actions = getActionsForTrees(srcTree, dstTree, c, i)

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

  def getTokensForTree(tree: TreeContext, commit: Commit, transactionIdx: Int) = {
    val tokens = tree.getRoot.preOrder.toList.map(n => {
      val nodeType = tree.getTypeLabel(n)
      val label = nodeType match {
        case "ForStatement" | "EnhancedForStatement" => "for"
        case "WhileStatement" => "while"
        case "DoStatement" => "do"
        case "IfStatement" => "if"
        case "ElseStatement" => "else"
        case "SwitchStatement" => "switch"
        case "SwitchCase" => "case"
        case "BreakStatement" => "break"
        case "ContinueStatement" => "continue"
        case "ThrowStatement" => "throw"
        case "TryStatement" => "try"
        case "CatchClause" => "catch"
        case "Finally" => "finally"
        case "SynchronizedStatement" => "synchronized"
        case "MethodInvocation" => if (n.getChildren.length > 1) n.getChild(1).getLabel else n.getLabel
        case "SimpleType" | "SimpleName" | "BooleanLiteral" | "NullLiteral" => n.getLabel
        case _ => ""
      }

      // position refers to the index of character in the file that contains the action node
      val position = n.getPos
      val token = ("Token", nodeType, label)
      val tokenLoc = (commit.commitId, transactionIdx, position)
      (token, tokenLoc)
    }).filterNot(t => disallowedTypes.contains(t._1._2))
    tokens
  }

  def generateCodeContext(commits: List[Commit], repoCorpus: String): Unit = {
    val completed = new AtomicInteger()

    val total = commits.flatMap(c => {
      c.transactions
        .filter(t => t.path.endsWith(".java"))
    }).size

    val codeContextIndex = commits.flatMap(c => {
      c.transactions
        .filter(t => t.path.endsWith(".java"))
        .zipWithIndex.flatMap{ case (t, i) => {
          val newFile = s"${repoCorpus}/${c.commitId}/${t.path}"
          val dstTree = Generators.getInstance().getTree(newFile)
          val tokens = getTokensForTree(dstTree, c, i)

          val done = completed.incrementAndGet()
          println(s"Processed ${done} / ${total} transactions")

          tokens
        }}
    })

    val codeContextFile = s"${repoCorpus}/code_context.txt"
    val writer = new BufferedWriter(new FileWriter(codeContextFile))
    codeContextIndex.foreach(c => {
      writer.write(s"${c._1._1},${c._1._2},${c._1._3},${c._2._1},${c._2._2},${c._2._3}\n")
    })
    writer.close
  }

  def main(args: Array[String]): Unit = {
    val repo = "../data/community-corpus/log4j"
    val repoCorpus = s"${repo}-corpus"
    val commits = getCommits(repoCorpus)
    Run.initGenerators()
    generateChangeContext(commits, repoCorpus)
    generateCodeContext(commits, repoCorpus)
  }
}
