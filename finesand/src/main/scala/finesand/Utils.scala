package finesand

import java.io.File

object Utils {
  def getListOfSubDirectories(directoryName: String): Seq[String] = {
    (new File(directoryName))
      .listFiles
      .filter(_.isDirectory)
      .map(_.getName)
      .toSeq
  }
}
