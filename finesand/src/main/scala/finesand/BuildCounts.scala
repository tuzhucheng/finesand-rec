package finesand

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.collection.parallel.immutable.ParVector
import scala.io.Source
import scala.util.{Try,Failure}
import sys.process._
import java.io.{BufferedWriter,File,FileWriter,FileOutputStream,ObjectOutputStream}

import com.github.gumtreediff.actions.ActionGenerator
import com.github.gumtreediff.actions.model.Action
import com.github.gumtreediff.client.Run
import com.github.gumtreediff.gen.Generators
import com.github.gumtreediff.io.TreeIoUtils
import com.github.gumtreediff.matchers.Matcher
import com.github.gumtreediff.matchers.Matchers
import com.github.gumtreediff.tree.{ITree,TreeContext}
import org.rogach.scallop._

import finesand.model.{Commit,PredictionPoint,Transaction}

object BuildCounts {

  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val repo = opt[String]() // "../data/community-corpus/log4j"
    val group = opt[Int](default = Some(1000), descr = "number of commits to group output files by")
    val lang = opt[String](default = Some("java"))
    verify()
  }

  type PredictionPointKey = (String, Int)
  type PredictionPointMapType = collection.mutable.Map[PredictionPointKey, PredictionPoint]
  type ActionType = ((String, String, String), (String, Int, Int, Int))
  type TokenType = ((String, String), (String, Int, Int, Int))
  val disallowedTypes = List("CompilationUnit", "PackageDeclaration", "ImportDeclaration")
  val Train = "train"
  val Test = "test"

  def getCommits(corpusDir: String, dataset: String): Seq[Commit] = {
    val dir = new File(corpusDir)
    if (!dir.exists || !dir.isDirectory)
      Seq[Commit]()

    val bufferedSource = Source.fromFile(s"${corpusDir}/${dataset}_commits.txt")
    val commitHashes = (for (line <- bufferedSource.getLines()) yield line).toSeq

    val commitDirs: Seq[File] = commitHashes.map(h => new File(s"${corpusDir}/${h}"))
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

  def getActionsForTrees(srcTree: TreeContext, dstTree: TreeContext, commit: Commit, transactionIdx: Int, predictionPoints: PredictionPointMapType): List[ActionType] = {
    val src = srcTree.getRoot
    val dst = dstTree.getRoot
    val matcher = Matchers.getInstance().getMatcher(src, dst)
    // Use `match` since match is a keyword in Scala
    matcher.`match`
    val generator = new ActionGenerator(src, dst, matcher.getMappings)
    generator.generate
    val actionList = generator.getActions.toList
    val midpoint = Math.round(Math.floor(actionList.length / 2)) + 1
    var predictionPt: Option[(PredictionPoint, Int)] = None

    val actions = actionList.zipWithIndex.map{ case (a, i) => {
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

      // get parent method pos for weighting
      var temp = node
      while (!temp.isRoot && dstTree.getTypeLabel(temp) != "MethodDeclaration") {
        temp = temp.getParent
      }
      val parentMethodPos = temp.getPos

      if (!predictionPt.isDefined && i >= midpoint && operationKind == "INS" && nodeType == "MethodInvocation" && node.getChildren.length > 1) {
        predictionPt = Some((new PredictionPoint(commit.commitId, transactionIdx, node.getChild(0).getLabel, node.getChild(1).getLabel, position, parentMethodPos), i))
      }

      val change = (operationKind, nodeType, label)
      val changeLoc = (commit.commitId, transactionIdx, position, parentMethodPos)
      (change, changeLoc)
    }}

    if (predictionPt.isDefined) {
      val (pp, actionIdx) = predictionPt.get
      val changeContext = new ListBuffer[PredictionPoint#ChangeContextScoreComponent]()
      val ppAction = actions(actionIdx)
      for (i <- 0 until actionIdx) {
        val action = actions(i)
        val scopeWeight = if (ppAction._2._4 == action._2._4) 1 else 0.5
        val depWeight = if (ppAction._1._3 == pp.variableName) 1 else 0.5
        val scoreComponent = ((action._1._1, action._1._2, action._1._3), scopeWeight, depWeight, action._2._3)
        changeContext += scoreComponent
      }
      pp.changeContext = Some(changeContext.toList)
      predictionPoints(pp.key) = pp
    }

    actions
  }

  def generateChangeContext(commits: Seq[Commit], repoCorpus: String, group: Int, predictionPoints: PredictionPointMapType, language: String, dataset: String): Unit = {
    val partialChangeContextIndex = commits.flatMap(c => {
      c.transactions
        .filter(t => t.path.endsWith(s".$language"))
        .zipWithIndex.flatMap{ case (t, i) => {
          val nameParts = t.path.split("/")
          val oldFilePath = (nameParts.init :+ ("old_" + nameParts.last)).mkString("/")
          val file1 = s"${repoCorpus}/${c.commitId}/${oldFilePath}"
          val file2 = s"${repoCorpus}/${c.commitId}/${t.path}"
          val actions : List[ActionType] =
            Try({
              val srcTree = Generators.getInstance().getTree(file1)
              val dstTree = Generators.getInstance().getTree(file2)
              getActionsForTrees(srcTree, dstTree, c, i, predictionPoints)
            }).recoverWith({
              case (ex: Throwable) => println("generatedChangeContext exception.."); Failure(ex)
            }).getOrElse(List.empty[ActionType])

          actions
        }}
    })

    if (!partialChangeContextIndex.isEmpty) {
      val testIndicator = if (dataset == Test) "test_" else ""
      val partialChangeContextFile = s"${repoCorpus}/change_context_${testIndicator}part_${group}.txt"
      val writer = new BufferedWriter(new FileWriter(partialChangeContextFile))
      partialChangeContextIndex.foreach(c => {
        writer.write(s"${c._1._1},${c._1._2},${c._1._3},${c._2._1},${c._2._2},${c._2._3},${c._2._4}\n")
      })
      writer.close
    }
  }

  def getTokensForTree(tree: TreeContext, commit: Commit, transactionIdx: Int, predictionPoints: PredictionPointMapType): List[TokenType] = {
    var predictionPt: Option[PredictionPoint] = predictionPoints get (commit.commitId, transactionIdx)

    val tokens = tree.getRoot.preOrder.toList
      .filterNot(t => disallowedTypes.contains(tree.getTypeLabel(t))).zipWithIndex
      .map{ case (n, i) => {
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

      // get parent method pos for weighting
      var temp = n
      while (!temp.isRoot && tree.getTypeLabel(temp) != "MethodDeclaration") {
        temp = temp.getParent
      }
      val parentMethodPos = temp.getPos

      val token = (nodeType, label)
      val tokenLoc = (commit.commitId, transactionIdx, position, parentMethodPos)
      (token, tokenLoc)
    }}

    if (predictionPt.isDefined) {
      val codeContext = new ListBuffer[PredictionPoint#CodeContextScoreComponent]()
      val pp = predictionPt.get
      tokens.foreach { case (token, tokenLoc) => {
        if (pp.pos > tokenLoc._3) {
          val scopeWeight = if (pp.parentPos == tokenLoc._4) 1 else 0.5
          val depWeight = if (pp.variableName == token._2) 1 else 0.5
          val scoreComponent = (token, scopeWeight, depWeight, tokenLoc._3)
          codeContext += scoreComponent
        }
      }}
      pp.codeContext = Some(codeContext.toList)
      predictionPoints((commit.commitId, transactionIdx)) = pp
    }

    tokens
  }

  def generateCodeContext(commits: Seq[Commit], repoCorpus: String, group: Int, predictionPoints: PredictionPointMapType, language: String, dataset: String): Unit = {
    val partialCodeContextIndex = commits.flatMap(c => {
      c.transactions
        .filter(t => t.path.endsWith(s".$language"))
        .zipWithIndex.flatMap{ case (t, i) => {
          val newFile = s"${repoCorpus}/${c.commitId}/${t.path}"
          val tokens: List[TokenType] = Try({
            val dstTree = Generators.getInstance().getTree(newFile)
            getTokensForTree(dstTree, c, i, predictionPoints)
          }).recoverWith({
            case (ex: Throwable) => println("generatedChangeContext exception.."); Failure(ex)
          }).getOrElse(List.empty[TokenType])

          tokens
        }}
    })

    if (!partialCodeContextIndex.isEmpty) {
      val testIndicator = if (dataset == Test) "test_" else ""
      val partialCodeContextFile = s"${repoCorpus}/code_context_${testIndicator}part_${group}.txt"
      val writer = new BufferedWriter(new FileWriter(partialCodeContextFile))
      partialCodeContextIndex.foreach(c => {
        writer.write(s"${c._1._1},${c._1._2},${c._2._1},${c._2._2},${c._2._3},${c._2._4}\n")
      })
      writer.close
    }
  }

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)
    val repo = conf.repo().stripSuffix("/")
    val group = conf.group()
    val lang = conf.lang()
    val repoCorpus = s"${repo}-corpus"

    Seq(Train, Test).foreach { s => {
      val commits = getCommits(repoCorpus, s)

      Run.initGenerators()
      commits.grouped(group).to[ParVector].zipWithIndex.foreach { case (commitsGroup, partNum) => {
        val predictionPoints: PredictionPointMapType = collection.mutable.Map()
        generateChangeContext(commitsGroup, repoCorpus, partNum, predictionPoints, lang, s)
        generateCodeContext(commitsGroup, repoCorpus, partNum, predictionPoints, lang, s)

        if (!predictionPoints.isEmpty) {
          val testIndicator = if (s == Test) "Test" else ""
          val oos = new ObjectOutputStream(new FileOutputStream(s"$repoCorpus/predictionPoints${testIndicator}Part${partNum}"))
          oos.writeObject(predictionPoints)
          oos.close
        }

        val from = partNum * group
        val to = Math.min(commits.length, (partNum + 1) * group)
        println(s"Processed commits ${from}-${to} out of ${commits.length}")
      }}
    }}
  }
}
