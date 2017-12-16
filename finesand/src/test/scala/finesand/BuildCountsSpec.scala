package finesand

import scala.collection.JavaConverters._
import scala.Console

import org.scalatest._

import com.github.gumtreediff.actions.model.Action
import com.github.gumtreediff.actions.ActionGenerator
import com.github.gumtreediff.client.Run;
import com.github.gumtreediff.gen._

import finesand.model.{Commit,Transaction,PredictionPoint}

object BuildCountsSpec extends FlatSpec with Matchers {

  type PredictionPointKey = (String, Int)
  type PredictionPointMapType = collection.mutable.Map[PredictionPointKey, PredictionPoint]

  "BuildCounts.getTokensForTree" should "generate correct token counts" in {
    Run.initGenerators();
    val file1 = "../examples/Fig1Before.java"
    val file2 = "../examples/Fig1After.java"
    val srcTc = Generators.getInstance().getTree(file1)
    val dstTc = Generators.getInstance().getTree(file2)

    val commit = new Commit("testcommit", None, List())
    val predictionPoints: PredictionPointMapType = collection.mutable.Map()
    val tokens = BuildCounts.getTokensForTree(dstTc, commit, 0, predictionPoints)
    val stream = new java.io.ByteArrayOutputStream()
    Console.withOut(stream) {
      println(tokens.length)
    }
  }

}
