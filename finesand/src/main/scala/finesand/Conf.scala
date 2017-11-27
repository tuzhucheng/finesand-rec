package finesand

import org.rogach.scallop._

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  val repo = opt[String]() // "../data/community-corpus/log4j"
  val branch = opt[String]()
  val group = opt[Int]()
  verify()
}
