import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.rogach.scallop._

object BuildModel {
  def main(args: Array[String]): Unit = {
    val sparkConf = new SparkConf().setAppName("finesand").setMaster("local")
    val sc = new SparkContext(sparkConf)
  }
}
