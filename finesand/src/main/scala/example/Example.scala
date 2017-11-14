import scala.collection.JavaConverters._

import com.github.gumtreediff.actions.model.Action
import com.github.gumtreediff.actions.ActionGenerator
import com.github.gumtreediff.client.Run;
import com.github.gumtreediff.gen._
import com.github.gumtreediff.matchers.Matchers

object Example {
    def main(args: Array[String]): Unit = {
        Run.initGenerators();
        val file1 = "../examples/Fig1Before.java"
        val file2 = "../examples/Fig1After.java"
        val srcTc = Generators.getInstance().getTree(file1)
        val dstTc = Generators.getInstance().getTree(file2)
        val (src, dst) = (srcTc.getRoot, dstTc.getRoot)

        val matcher = Matchers.getInstance().getMatcher(src, dst)
        val g = new ActionGenerator(src, dst, matcher.getMappings())
        g.generate()
        val actions = g.getActions().asScala

        println("Action:")
        actions.foreach(a => println(a.format(dstTc)))
    }
}
