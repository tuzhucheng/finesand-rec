package finesand

object ChangeContextCorpusBuilderDriver {
    def main(args: Array[String]): Unit = {
        val repo = "../data/community-corpus/log4j"
        val (trainCommits, testCommits) = TrainTestSplit.split(repo, 0.9)
        println(trainCommits(0))
    }
}
