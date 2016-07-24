package org.clulab.twitter4food.featureclassifier

import org.clulab.learning._
import org.clulab.struct.Counter
import org.clulab.twitter4food.struct._
import org.clulab.twitter4food.util._
import java.io._

/** Gender classifier that predicts if a given twitter account is M (male)
  * or F (female)
  * @author adikou
  * @date 03-13-2016
  */
class GenderClassifier(
  useUnigrams: Boolean = true,
  useBigrams: Boolean = false,
  useTopics: Boolean = false,
  useDictionaries: Boolean = false,
  useEmbeddings: Boolean = false,
  useCosineSim: Boolean = false,
  useFollowers: Boolean = false,
  useGender: Boolean = false,
  useRace: Boolean = false,
  datumScaling: Boolean = false,
  featureScaling: Boolean = false)
  extends ClassifierImpl(
    useUnigrams,
    useBigrams,
    useTopics,
    useDictionaries,
    useEmbeddings,
    useCosineSim,
    useFollowers,
    useGender,
    useRace,
    datumScaling,
    featureScaling,
    variable = "gender"
  )

object GenderClassifier {
  def main(args: Array[String]): Unit = {
    val params = TestUtils.parseArgs(args)
    TestUtils.init(0)
    val gc = new GenderClassifier(
      params.useUnigrams, params.useBigrams,
      params.useTopics, params.useDictionaries, params.useEmbeddings, params.useCosineSim,
      params.useFollowers, params.useGender, params.useRace,
      params.datumScaling, params.featureScaling)
    gc.runTest(args, "gender")
  }
}