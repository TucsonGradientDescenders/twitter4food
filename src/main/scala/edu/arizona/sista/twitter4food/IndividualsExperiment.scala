package edu.arizona.sista.twitter4food

import edu.arizona.sista.learning._
import edu.arizona.sista.struct._
import Utils._
import java.io._
import java.util.Properties
import scala.collection.{immutable, mutable, GenSeq}
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.util._
import StateExperiment._
import Mixins._
import de.bwaldvogel.liblinear.SolverType
import scala.io.Source
import collection.JavaConversions._
import edu.arizona.sista.utils.EvaluationStatistics

/**
 * Created by dfried on 5/6/15.
 */
class IndividualsExperiment(parameters: ExperimentParameters, printWriter: PrintWriter = new java.io.PrintWriter(System.out))
  extends Experiment(parameters = parameters, printWriter = printWriter) {

  def run(trainingCorpus: Seq[IndividualsTweets], testingCorpus: Seq[IndividualsTweets]) = {

    val trainingTweets = trainingCorpus.map(_.tweets)
    val trainingLabels = trainingCorpus.map(_.label.get)

    val testingTweets = testingCorpus.map(_.tweets)
    val testingLabels = testingCorpus.map(_.label.get)

    val (trainingFeatures, filterFn) =  mkViewFeatures(parameters.lexicalParameters.ngramThreshold)(trainingTweets)
    val testingFeatures = mkViewFeatures(None)(testingTweets)._1.map(_.filter(p => filterFn(p._1)))

    val labels = (trainingLabels ++ testingLabels).toSet

    // keep track of the highly weighted features for each label. This will be updated by updateWeights in each fold
    // of the cross validation
    val weights = new mutable.HashMap[Int, Counter[String]]()
    for (label <- labels) weights(label) = new Counter[String]

    def updateWeights(clf: Classifier[Int, String]) = {
      val marginalWeights: Option[Map[Int, Counter[String]]] = clf match {
        case clf:LiblinearClassifier[Int, String] => Some(clf.getWeights(verbose = false))
        //case clf:BaggingClassifier[L, String] => clf.getWeights
        case _ => None
      }
      if (marginalWeights != None) {
        for ((l, w) <- marginalWeights.get) {
          println(w.sorted.take(10) mkString(" "))
          // normalize the weights and add them into the total
          weights(l) = weights(l) + w / Math.sqrt(w.dotProduct(w))
        }
      }
    }

    val (clf, procFeats, featureSelector) = trainFromFeatures(trainingFeatures, trainingLabels)

    val predictedLabels: Seq[Int] = for {
      testF <- testingFeatures
      // make a datum for the individual (label will not be used!)
      testingDatum = mkDatum(0, procFeats(testF))
      _ = { // this funky notation just allows us to do side effects in the for comprehension,
        // specifically updating the feature weights
        println(clf.toString)
        if (featureSelector != None)
          println(featureSelector.get.featureScores.toSeq.sortBy(_._2).reverse.take(20))
        if (parameters.classifierType == SVM_L1 || parameters.classifierType == SVM_L2) {
          // get the feature weights for this state for the true class
          updateWeights(clf)
        }
      }
      prediction = clf.classOf(testingDatum)
    } yield prediction

    (predictedLabels, weights)
  }

}

object IndividualsExperiment {
  import Experiment._

  def makeBaselineTrainingAndTesting(numClasses: Int, removeMarginals: Option[Int])(trainingCorpus: IndividualsCorpus, testingCorpus: Option[LabelledIndividualsCorpus]): (Seq[IndividualsTweets], Seq[IndividualsTweets]) = {
    // maps from state abbreviations to integer labels
    val stateLabels = Experiment.makeLabels(Datasets.overweight, numClasses, removeMarginals)

    // take a mapping from state abbvs to a dictionary of userName -> tweets
    // return three lists: the user tweets, usernames, and the labels for those tweets (assuming each user has his/her state's label)
    def propLabels(tweetsByUserByState: Map[String, Map[String, Seq[Tweet]]]): Seq[IndividualsTweets] = for {
      (state, tweetsByUser) <- tweetsByUserByState.toSeq
      (username, tweets) <- tweetsByUser
    } yield IndividualsTweets(tweets, username, Some(stateLabels(state)), Some(state))

    val (trainingTweets, testingTweets) = testingCorpus match {
      case None => {
        (propLabels(trainingCorpus.trainingTweets), propLabels(trainingCorpus.testingTweets))
      }
      case Some(testC) => {
        (propLabels(trainingCorpus.allTweets), testC.tweets)
      }
    }

    (trainingTweets, testingTweets)
  }

  // return the number correctly predicted and the total
  def labelledAccuracy(tweetsWithPredictedLabels: Seq[(IndividualsTweets, Int)]): (Int, Int) = {
    val correctlyPredicted = tweetsWithPredictedLabels.filter({
      case (tweets, predictedLabel) => tweets.label.get == predictedLabel
    }).size
    (correctlyPredicted, tweetsWithPredictedLabels.size)
  }

  def main(args: Array[String]) {

    val predictCelebrities = false

    // Some(k) to remove the k states closest to the bin edges when binning numerical data into classification,
    // or None to use all states
    val removeMarginals: Option[Int] = None
    // how many classes should we bin the numerical data into for classification?
    val numClasses = 2

    println(s"heap size: ${Runtime.getRuntime.maxMemory / (1024 * 1024)}")

    val outFile = if (args.size > 0) args(0) else null

    val individualsCorpus = new IndividualsCorpus("/data/nlp/corpora/twitter4food/foodSamples-20150501", numToTake=Some(10))

    val testCorpus = if (predictCelebrities) {
      val celebrityCorpus = new LabelledIndividualsCorpus("/data/nlp/corpora/twitter4food/testDataset/newUsers.csv", "/data/nlp/corpora/twitter4food/testDataset/newUsers")
      Some(celebrityCorpus)
    } else {
      None
    }

    val (trainingTweets, testingTweets) = makeBaselineTrainingAndTesting(numClasses, removeMarginals)(individualsCorpus, testCorpus)

    val pw: PrintWriter = if (outFile != null) (new PrintWriter(new java.io.File(outFile))) else (new PrintWriter(System.out))

    // create many possible variants of the experiment parameters, and for each map to results of running the
    // experiment
    // notation: = assigns a parameter to a single value
    //           <- means the parameter will take on all of the values in the list in turn
    val predictionsAndWeights = (for {
    // which base tokens to use? e.g. food words, hashtags, all words
      tokenTypes: TokenType <- List(AllTokens, HashtagTokens, FoodTokens, FoodHashtagTokens).par
      // which annotators to use in addition to tokens?
      annotators <- List(
        //List(LDAAnnotator(tokenTypes), SentimentAnnotator),
        //List(SentimentAnnotator),
        // List(LDAAnnotator(tokenTypes)),
        List())
      // classifierType <- List(RandomForest, SVM_L2)
      classifierType: ClassifierType <- List(SVM_L2)
      // type of normalization to perform: normalize across a feature, across a state, or not at all
      // this has been supplanted by our normalization by the number of tweets for each state
      normalization = NoNorm
      // only keep ngrams occurring this many times or more
      ngramThreshold = Some(5)
      // split feature values into this number of quantiles
      numFeatureBins = classifierType match {
        case RandomForest => Some(3)
        case _ => None
      }
      // use a bias in the SVM?
      useBias = false
      // use regions as features?
      regionType = NoRegions

      // Some(k) to use k classifiers bagged, or None to not do bagging
      baggingNClassifiers <- List(None)
      // force use of features that we think will be informative in random forests?
      forceFeatures = classifierType match {
        case RandomForest => true
        case _ => false
      }
      // Some(k) to keep k features ranked by mutual information, or None to not do this
      miNumToKeep: Option[Int] = None
      // Some(k) to limit random forest tree depth to k levels, or None to not do this
      maxTreeDepth: Option[Int] = Some(3)
      // these were from failed experiments to use NNMF to reduce the feature space
      //reduceLexicalK: Option[Int] = None
      //reduceLdaK: Option[Int] = None

      params = new ExperimentParameters(new LexicalParameters(tokenTypes, annotators, normalization, ngramThreshold, numFeatureBins),
        classifierType, useBias, regionType, baggingNClassifiers, forceFeatures, numClasses,
        miNumToKeep, maxTreeDepth, removeMarginals)
    // Try is an object that contains either the results of the method inside or an error if it failed
    } yield params -> new IndividualsExperiment(params, pw).run(trainingTweets, testingTweets)).seq

    def indexedMap[L](xs: Seq[L]) = (for {
      (x, i) <- xs.zipWithIndex
    } yield i -> x).toMap

    for ((params, (predictions, weights)) <- predictionsAndWeights.sortBy(_._1.toString)) {
      pw.println(params)

      val labelledInstances: Seq[(IndividualsTweets, Int)] = testingTweets zip predictions

      val (correct, total) = labelledAccuracy(labelledInstances)
      pw.println(s"overall accuracy\t${correct} / ${total}\t${correct.toDouble / total * 100.0}%")
      pw.println

      val byClass: Map[Int, Seq[(IndividualsTweets, Int)]] = labelledInstances.groupBy(_._1.label.get)

      val byClassAccuracy = byClass.mapValues(labelledAccuracy).toMap
      // print the per-class accuracy
      for ((class_, (correct, total)) <- byClassAccuracy) {
        pw.println("accuracy by class")
        pw.println(s"class ${class_}\t${correct} / ${total}\t${correct.toDouble / total * 100.0}%")
        pw.println
      }

      if (predictCelebrities) {
        // print each prediction
        for ((tweets, prediction) <- labelledInstances.sortBy( { case (it, prediction) => (it.label.get, it.username) } )) {
          pw.println(s"${tweets.username}\tact: ${tweets.label.get}\tpred: ${prediction}")
        }
      } else {
        // print predictions by state
        for ((state, statesInstances) <- labelledInstances.groupBy( { case (it, prediction)  => it.state.get }).toSeq.sortBy(_._1)) {
          val (correct, total) = labelledAccuracy(statesInstances)
          pw.println(s"${state}\t${correct} / ${total}\t${correct.toDouble / total * 100.0}%")
        }
      }

      pw.println
      pw.println
    }


    pw.println
    pw.println("feature weights")

    for ((params, resultsByDataset) <- predictionsAndWeights.sortBy(_._1.toString)) {
      pw.println(params)
      printWeights(pw, resultsByDataset._2.toMap)
    }

    pw.println
    pw.println

    if (outFile != null) {
      try {
      } finally { pw.close() }
    } else {
      pw.flush()
    }
  }

}