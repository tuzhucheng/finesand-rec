package finesand

object ModelUtils {
  implicit def bool2int(b:Boolean) = if (b) 1 else 0

  // Get top-1,2,3,4,5,10 accuracy
  def getAccuracy(predictions: Seq[(String, Array[(String, Double)])], vocab: Array[String]) = {
    val ks = List(1, 2, 3, 4, 5, 10)
    val oovAcc = predictions.map { case (goldMethodName, topK) => {
      ks.map(k => if (topK.toStream.map(_._1).take(k).contains(goldMethodName)) 1 else 0)
    }}
    val oovHits = oovAcc.transpose.map(l => l.reduce(_ + _))
    val oovTotal = List.fill(6)(oovAcc.size)
    val oovFinal = oovHits.zip(oovTotal).map(t => t._1 * 100.0 / t._2).toList

    val inAcc = predictions.filter(kv => vocab.contains(kv._1)).map { case (goldMethodName, topK) => {
      ks.map(k => if (topK.toStream.map(_._1).take(k).contains(goldMethodName)) 1 else 0)
    }}
    val inHits = inAcc.transpose.map(l => l.reduce(_ + _))
    val inTotal = List.fill(6)(inAcc.size)
    val inFinal = inHits.zip(inTotal).map(t => t._1 * 100.0 / t._2).toList
    println("Number of Predictions:", oovAcc.size)
    println("Number of Predictions (in-vocab):", inAcc.size)

    ks.zipWithIndex.map{ case (k, i) => k -> Map("oov" -> oovFinal(i), "in" -> inFinal(i)) }
  }

  def getMAP(predictions: Seq[(String, Array[(String, Double)])], vocab: Array[String] = Array.empty[String]) = {
    val queries = vocab.isEmpty match {
      case true => predictions
      case false => predictions.filter { case (k, v) => vocab.contains(k) }
    }
    val Q = queries.length  // num of queries
    val docsPerQuery = queries(0)._2.size
    val meanAP = queries.map { case (q, candidates) => {
      val foundAt = candidates.map(_._1).indexOf(q)
      var precisionSum = 0.0
      if (foundAt != -1) {
        for (numRetrievedItems <- foundAt+1 to docsPerQuery) precisionSum += 1.0 / numRetrievedItems
      }
      precisionSum / docsPerQuery
    }}.sum / Q
    meanAP
  }

  def aggregateScoreAndTakeTop(predictions: Seq[(String, Array[(String, (Double, Double))])], wc: Double, top: Int) = {
    val aggregated = predictions.toSeq.map { case (goldMethodName, topK) => {
      val aggregatedList = topK.map { case (api, scores) => (api, wc*scores._1 + (1-wc)*scores._2)}
      (goldMethodName, aggregatedList.sortWith(_._2 > _._2).take(top))
    }}
    aggregated
  }
}
