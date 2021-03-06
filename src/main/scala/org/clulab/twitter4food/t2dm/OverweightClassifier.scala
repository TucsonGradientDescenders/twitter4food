package org.clulab.twitter4food.t2dm

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.{Files, Paths}
import org.slf4j.LoggerFactory

import com.typesafe.config.ConfigFactory
import org.clulab.learning.{L1LinearSVMClassifier, LiblinearClassifier}
import org.clulab.twitter4food.featureclassifier.ClassifierImpl
import org.clulab.twitter4food.struct.TwitterAccount
import org.clulab.twitter4food.util.{Eval, FileUtils, TestUtils}

/**
  * Created by Terron on 2/15/16.
  *
  * A classifier for classifying a TwitterAccount as "Overweight" or "Not overweight".
  *
  * All parameters are consistent with those in FeatureExtractor
  */
class OverweightClassifier(
  useUnigrams: Boolean = true,
  useBigrams: Boolean = false,
  useTopics: Boolean = false,
  useDictionaries: Boolean = false,
  useEmbeddings: Boolean = false,
  useCosineSim: Boolean = false,
  useFollowers: Boolean = false,
  useFollowees: Boolean = false,
  useGender: Boolean = false,
  useRace: Boolean = false,
  datumScaling: Boolean = false,
  featureScaling: Boolean = false)
  extends ClassifierImpl(
    useUnigrams=useUnigrams,
    useBigrams=useBigrams,
    useTopics=useTopics,
    useDictionaries=useDictionaries,
    useEmbeddings=useEmbeddings,
    useCosineSim=useCosineSim,
    useFollowers=useFollowers,
    useFollowees=useFollowees,
    useGender=useGender,
    useRace=useRace,
    datumScaling=datumScaling,
    featureScaling=featureScaling,
    variable = "overweight")

object OverweightClassifier {

  val logger = LoggerFactory.getLogger(this.getClass)

  def main(args: Array[String]) {
    // Parse args using standard Config
    val params = TestUtils.parseArgs(args)
    val config = ConfigFactory.load

    // Allow user to specify if model should be loaded or overwritten
    var loadModel = false
    print("\n\nOverwrite existing model file? (yes/no) ")
    var answer = scala.io.StdIn.readLine()
    if (answer.toLowerCase.charAt(0) == 'n') {
      loadModel = true
      println("\tUse existing model file")
    } else
      println("\tOverwrite existing model file")

    var testOnDev = true
    print("Partition to test on? (dev/test) ")
    answer = scala.io.StdIn.readLine()
    if (answer.toLowerCase.charAt(0) == 't') {
      testOnDev = false
      println("\tTraining on train+dev, testing on test")
    } else
      println("\tTraining on train, testing on dev\n\n")


    // Instantiate classifier after prompts in case followers are being used (file takes a long time to load)
    val oc = new OverweightClassifier(
      useUnigrams = params.useUnigrams,
      useBigrams = params.useBigrams,
      useTopics = params.useTopics,
      useDictionaries = params.useDictionaries,
      useEmbeddings = params.useEmbeddings,
      useCosineSim = params.useCosineSim,
      useFollowers = params.useFollowers,
      useFollowees = params.useFollowees,
      useGender = params.useGender,
      useRace = params.useRace,
      datumScaling = params.datumScaling,
      featureScaling = params.featureScaling)

    val fileExt = args.mkString("").replace("-", "").sorted

    val outputDir = config.getString("classifier") + "/overweight/results/" + fileExt
    if (!Files.exists(Paths.get(outputDir))) {
      if (new File(outputDir).mkdir())
        logger.info(s"Created output directory $outputDir")
      else
        logger.info(s"ERROR: failed to create output directory $outputDir")
    }

    //        oc.runTest(args, "overweight", outputDir + "/results.txt")
    val modelFile = s"${config.getString("overweight")}/model/${fileExt}.dat"

    // Load classifier if model exists
    if ( loadModel && Files.exists(Paths.get(modelFile)) ) {
      logger.info("Loading model from file...")
      val cl = LiblinearClassifier.loadFrom[String, String](modelFile)
      oc.subClassifier = Some(cl)
    } else if (testOnDev) { // evaluating on dev
      logger.info("Loading training accounts...")
      val trainingData = FileUtils.load(config.getString("classifiers.overweight.trainingData"))

      // Train classifier and save model to file
      logger.info("Training classifier...")
      oc.setClassifier(new L1LinearSVMClassifier[String, String]())
      oc.train(trainingData.keys.toSeq, trainingData.values.toSeq)
      oc.subClassifier.get.saveTo(modelFile)
    } else { // evaluating on test, training on train + dev
      logger.info("Loading training accounts...")
      val trainingData = FileUtils.load(config.getString("classifiers.overweight.trainingData"))
      logger.info("Loading dev accounts...")
      val devData = FileUtils.load(config.getString("classifiers.overweight.devData"))

      val toTrainOn = trainingData ++ devData

      // Train classifier and save model to file
      logger.info("Training classifier...")
      oc.setClassifier(new L1LinearSVMClassifier[String, String]())
      oc.train(toTrainOn.keys.toSeq, toTrainOn.values.toSeq)
      oc.subClassifier.get.saveTo(modelFile)
    }

    val testSet: Map[TwitterAccount, String] = if (testOnDev) {
      logger.info("Loading dev accounts...")
      FileUtils.load(config.getString("classifiers.overweight.devData"))
    } else {
      logger.info("Loading test accounts...")
      FileUtils.load(config.getString("classifiers.overweight.testData"))
    }

    // Set progress bar
    val pb = new me.tongfei.progressbar.ProgressBar("main()", 100)
    pb.start()
    pb.maxHint(testSet.size)
    pb.setExtraMessage("Testing on dev accounts...")

    // Classify accounts
    val testSetLabels = testSet.values.toSeq
    val predictedLabels = testSet.keys.toSeq.map(u => { pb.step(); oc.classify(u); })

    pb.stop()

    // Print results
    val (evalMeasures, microAvg, macroAvg) = Eval.evaluate(testSetLabels, predictedLabels,
      testSet.keys.toSeq)

    val evalMetric = evalMeasures("Overweight")
    val precision = evalMetric.P
    val recall = evalMetric.R

    if (params.fpnAnalysis & oc.subClassifier.nonEmpty) {
      // Perform analysis on false negatives and false positives
      println("False negatives:")
      evalMetric.FNAccounts.foreach(account => print(account.handle + "\t"))
      println("\n====")
      outputAnalysis(outputDir + "/analysisFN.txt", "*** False negatives ***\n\n", evalMetric.FNAccounts, oc)

      println("False positives:")
      evalMetric.FPAccounts.foreach(account => print(account.handle + "\t"))
      println("\n====")
      outputAnalysis(outputDir + "/analysisFP.txt", "*** False positives ***\n\n", evalMetric.FPAccounts, oc)
    }

    println("\nResults:")
    println(s"Precision: $precision")
    println(s"Recall: $recall")
    println(s"F-measure (harmonic mean): ${fMeasure(precision, recall, 1)}")
    println(s"F-measure (recall 5x): ${fMeasure(precision, recall, .2)}")
    println(s"Macro average: $macroAvg")
    println(s"Micro average: $microAvg")

    // Save results
    val writer = new BufferedWriter(new FileWriter(outputDir + "/analysisMetrics.txt", false))
    writer.write(s"Precision: $precision\n")
    writer.write(s"Recall: $recall\n")
    writer.write(s"F-measure (harmonic mean): ${fMeasure(precision, recall, 1)}\n")
    writer.write(s"F-measure (recall 5x): ${fMeasure(precision, recall, .2)}\n")
    writer.write(s"Macro average: $macroAvg\n")
    writer.write(s"Micro average: $microAvg\n")
    writer.close()

    // Save individual predictions for bootstrap significance
    val predicted = new BufferedWriter(new FileWriter(outputDir + "/predicted.txt", false))
    predicted.write(s"gold\tpred\n")
    testSetLabels.zip(predictedLabels).foreach(acct => predicted.write(s"${acct._1}\t${acct._2}\n"))
    predicted.close()
  }

  private def outputAnalysis(outputFile:String, header:String, accounts: Seq[TwitterAccount], oc: OverweightClassifier): Unit = {
    // Set progress bar
    var numAccountsToPrint = 20
    val numWeightsToPrint = 30
    val pb = new me.tongfei.progressbar.ProgressBar("outputAnalysis()", 100)
    pb.start()
    pb.maxHint(numAccountsToPrint)
    pb.setExtraMessage(header)

    // Initialize writer
    val writer = new BufferedWriter(new FileWriter(outputFile, false))
    var isFirst = true
    writer.write(header)

    // Iterate over accounts
    for (account <- accounts) {
      if (numAccountsToPrint > 0) {
        // Analyze account
        val (topWeights, dotProduct) = TestUtils.analyze(oc.subClassifier.get, Set("Overweight", "Not overweight"),
          account, oc.featureExtractor)
        // Only print the general weights on the features once
        if (isFirst) {
          for ((label, sequence) <- topWeights) {
            writer.write(s"Top weights for $label:\n")
            var numToPrint = numWeightsToPrint
            for ((feature, score) <- sequence) {
              if ((numToPrint > 0) && (score > 0.0)) {
                writer.write(s"$feature -> $score\n")
                numToPrint = numToPrint - 1
              }
            }
            writer.write("================================\n")
          }
          isFirst = false
        }
        // Print hadamard product for every account
        writer.write(s"Hadamard product for ${account.handle}:\n")
        for ((label, sequence) <- dotProduct) {
          if (label equals "Overweight") {
            var numToPrint = numWeightsToPrint
            for ((feature, score) <- sequence) {
              if ((numToPrint > 0) && (score > 0.0)) {
                writer.write(s"$feature -> $score\n")
                numToPrint = numToPrint - 1
              }
            }
          }
        }
        writer.write("================================\n")
      }
      pb.step()
      numAccountsToPrint -= 1
    }
    writer.close
    pb.stop()
  }

  private def fMeasure(precision: Double, recall: Double, beta: Double): Double =
    (1 + Math.pow(beta, 2)) * ((precision * recall) / (Math.pow(beta, 2) * precision + recall))
}