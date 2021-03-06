package org.clulab.twitter4food.util

import java.io.File

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.util.Random

/**
  * Test for one-tailed statistical significance of all systems compared to a named baseline
  * @author Dane Bell
  */
object BootstrapSignificance {

  case class Config(
    useOverweightF1:Boolean = false,
    useMicroF1:Boolean = false,
    useMacroF1:Boolean = false,
    repetitions:Int = 10000)

  /**
    * Micro F1, i.e. harmonic mean of precision and recall across all items regardless of true class.
    * This biases the results toward larger classes, which may be the desired behavior.
    * @param gold true labels
    * @param pred predicted labels
    * @return micro F1 (0.0 - 1.0 range)
    */
  def microF1(gold: Seq[String], pred: Seq[String]): Double = {
    assert(gold.length == pred.length)
    val tp: Double = (for (i <- gold.indices) yield if (gold(i) == pred(i)) 1 else 0).sum
    val fn: Double = (for {
      lbl <- gold.distinct
      i <- gold.indices
      if gold(i) == lbl && pred(i) != lbl
    } yield 1).sum
    val fp: Double = (for {
      lbl <- gold.distinct
      i <- gold.indices
      if gold(i) != lbl && pred(i) == lbl
    } yield 1).sum

    if (tp == 0) 0 else 2.0 * tp / (2.0 * tp + fn + fp)
  }

  /**
    * The harmonic mean of precision and recall is calculated for each class, then these are averaged.
    * This makes every class as "important," in that the score is not biased by the size of the class.
    * @param gold true labels
    * @param pred predicted labels
    * @return macro F1 (0.0 - 1.0 range)
    */
  def macroF1(gold: Seq[String], pred: Seq[String]): Double = {
    assert(gold.length == pred.length)
    val labels = gold.distinct

    val tps = for {
      lbl <- labels
      tpForLabel = (for (i <- gold.indices) yield if (gold(i) == pred(i) && gold(i) == lbl) 1.0 else 0.0).sum
    } yield tpForLabel

    val fns = for {
      lbl <- gold.distinct
      fnForLabel = (for (i <- gold.indices) yield if (gold(i) == lbl && pred(i) != lbl) 1.0 else 0.0).sum
    } yield fnForLabel

    val fps = for {
      lbl <- gold.distinct
      fpForLabel = (for (i <- gold.indices) yield if (gold(i) != lbl && pred(i) == lbl) 1.0 else 0.0).sum
    } yield fpForLabel

    // problematic if the number of classes is huge
    val tp: Double = tps.sum / tps.length
    val fn: Double = fns.sum / fns.length
    val fp: Double = fps.sum / fps.length

    if (tp == 0) 0 else 2.0 * tp / (2.0 * tp + fn + fp)
  }

  /**
    * F1 score just for the Overweight class (neither micro nor macro averaging)
    * @param gold true labels
    * @param pred predicted labels
    * @return F1 score (0.0 - 1.0 range)
    */
  def overweightF1(gold: Seq[String], pred: Seq[String]): Double = {
    assert(gold.length == pred.length)
    val tp = (for (i <- gold.indices) yield if (gold(i) == "Overweight" && pred(i) == "Overweight") 1.0 else 0.0).sum
    val fp = (for (i <- gold.indices) yield if (gold(i) != "Overweight" && pred(i) == "Overweight") 1.0 else 0.0).sum
    val fn = (for (i <- gold.indices) yield if (gold(i) == "Overweight" && pred(i) != "Overweight") 1.0 else 0.0).sum
    if (tp == 0) 0 else 2.0 * tp / (2.0 * tp + fn + fp)
  }

  /**
    * Recall just for the Overweight class
    * @param gold true labels
    * @param pred predicted labels
    * @return recall score (0.0 - 1.0 range)
    */
  def owRecall(gold: Seq[String], pred: Seq[String]): Double = {
    assert(gold.length == pred.length)
    val owTrue = gold.indices.filter(ix => gold(ix) == "Overweight")
    val owFound = owTrue.count(ix => pred(ix) == "Overweight")
    owFound.toDouble / owTrue.length.toDouble
  }

  /**
    * Precision just for the Overweight class
    * @param gold true labels
    * @param pred predicted labels
    * @return precision score (0.0 - 1.0 range)
    */
  def owPrecision(gold: Seq[String], pred: Seq[String]): Double = {
    assert(gold.length == pred.length)
    val owFound = pred.indices.filter(ix => pred(ix) == "Overweight")
    val owTrue = owFound.count(ix => gold(ix) == "Overweight")
    owTrue.toDouble / owFound.length.toDouble
  }

  def main(args: Array[String]): Unit = {
    def parseArgs(args: Array[String]): Config = {
      val parser = new scopt.OptionParser[Config]("bootstrapping") {
        head("bootstrapping", "0.x")
        opt[Unit]('o', "overweightF1") action { (x, c) =>
          c.copy(useOverweightF1 = true)} text "use only F1 for the overweight class"
        opt[Unit]('i', "microF1") action { (x, c) =>
          c.copy(useMicroF1 = true)} text "use microF1"
        opt[Unit]('a', "macroF1") action { (x, c) =>
          c.copy(useMacroF1 = true)} text "use macroF1"
        opt[Int]('r', "repetitions") action { (x, c) =>
          c.copy(repetitions = x) } text "number of repetitions in bootstrap"
      }

      parser.parse(args, Config()).get
    }

    val logger = LoggerFactory.getLogger(this.getClass)
    val config = ConfigFactory.load

    val baselineFeatures = config.getString("classifiers.overweight.baseline")
    val predictionDir = new File(config.getString("classifiers.overweight.results"))
    val params = parseArgs(args)

    // Prefer overweightF1 >> microF1 >> macroF1
    def scoreMetric(gold: Seq[String], pred: Seq[String]): Double =
      if (params.useOverweightF1) overweightF1(gold, pred)
      else if (params.useMicroF1) microF1(gold, pred)
      else if (params.useMacroF1) macroF1(gold, pred)
      else overweightF1(gold, pred)

    // The directory of results files must exist
    assert(predictionDir.exists && predictionDir.isDirectory)
    val folders = predictionDir.listFiles.filter(_.isDirectory)

    // gather each set of (gold data, prediction) from each result available
    // IndexedSeq for very frequent indexing later
    val predictionsWithGold: Map[String, IndexedSeq[(String, String)]] = (for {
        folder <- folders.toSeq
        if folder.list.contains("predicted.txt")
        predFile = scala.io.Source.fromFile(folder.getPath + "/predicted.txt")
        preds = predFile
          .getLines
          .map(_.stripLineEnd.split("\t"))
          .map(line => (line(0), line(1)))
          .toIndexedSeq
          .tail // first row is header info
          .sortBy(_._1) // gold columns are in different orders, so sort
      } yield folder.getName -> preds).toMap

    // If the baseline is not present, we can't compare against it.
    assert(predictionsWithGold.keys.toSeq.contains(baselineFeatures))

    val (gold, baseline) = predictionsWithGold(baselineFeatures).unzip

    // Ignore results that have different Gold annotations and thus different users or user order
    val comparable = predictionsWithGold.filter(pred => pred._2.unzip._1 == gold)
    val incomparable = predictionsWithGold.keySet diff comparable.keySet
    if(incomparable.nonEmpty) {
      logger.debug(s"""$incomparable did not have the same gold annotations as baseline""")
    }

    val predictions = comparable.map(featureSet => featureSet._1 -> featureSet._2.unzip._2)

    // initialize a buffer for tracking whether each model's F1 exceeds the baseline
    val betterThanBaseline: Map[String, scala.collection.mutable.ListBuffer[Double]] = (for {
      key <- predictions.keys
    } yield key -> new scala.collection.mutable.ListBuffer[Double]).toMap

    logger.info(s"repetitions: ${params.repetitions}, models: ${predictions.size}")

    val pb = new me.tongfei.progressbar.ProgressBar("bootstrap", 100)
    pb.start()
    pb.maxHint(params.repetitions * betterThanBaseline.size)
    pb.setExtraMessage("sampling...")

    // for each rep, randomly sample indices once, then compare the baseline's F1 to each other model's
    for {
      i <- 0 until params.repetitions
      sampleIdx = for (j <- gold.indices) yield Random.nextInt(gold.length - 1) // random sample with replacement
      sampleGold = for (j <- sampleIdx) yield gold(j) // ground truth labels for sampled accts
      featureSet <- predictions.keys  // same sample applied to each eligible featureSet
      pred = predictions(featureSet)
      samplePred = for (j <- sampleIdx) yield pred(j) // comparison predictions for sampled accts
      sampleBase = for (j <- sampleIdx) yield baseline(j) // baseline predictions for sampled accts
    } {
      val baselineF1 = scoreMetric(sampleGold, sampleBase)
      val predF1 = scoreMetric(sampleGold, samplePred)
      betterThanBaseline(featureSet).append(if (predF1 > baselineF1) 1.0 else 0.0)
      pb.step()
    }

    pb.stop()

    // Calculate all stats just to be sure.
    val prec = (for (k <- predictions.keys) yield k -> owPrecision(gold, predictions(k))).toMap
    val recall = (for (k <- predictions.keys) yield k -> owRecall(gold, predictions(k))).toMap
    val owF1 = (for (k <- predictions.keys) yield k -> overweightF1(gold, predictions(k))).toMap
    val iF1 = (for (k <- predictions.keys) yield k -> microF1(gold, predictions(k))).toMap
    val aF1 = (for (k <- predictions.keys) yield k -> macroF1(gold, predictions(k))).toMap

    // print out results
    println("model\tprecision\trecall\toverweight F1\tmicro F1\tmacro F1\tpval")
    betterThanBaseline.toSeq.sortBy(_._1).reverse.foreach{
      case (featureSet, isBetter) =>
        val baselineLabel = if (featureSet == baselineFeatures) " (baseline)" else ""
        println(f"$featureSet$baselineLabel\t${prec(featureSet)}%1.4f\t${recall(featureSet)}%1.4f\t" +
          f"${owF1(featureSet)}%1.4f\t${iF1(featureSet)}%1.4f\t${aF1(featureSet)}%1.4f\t" +
          f"${1.0 - isBetter.sum / params.repetitions.toDouble}%1.4f")
    }
  }
}