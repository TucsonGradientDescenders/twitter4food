package org.clulab.twitter4food.util

import java.io.File

import org.apache.commons.io.FilenameUtils
import org.clulab.twitter4food.struct.FeatureExtractor.filterTags
import org.clulab.twitter4food.struct.TwitterAccount
import org.slf4j.LoggerFactory

object Tokenize {
  def main(args: Array[String]): Unit = {
    val logger = LoggerFactory.getLogger(this.getClass)

    if (args.length < 1) println("Please specify path and file to tokenize")
    val accounts = FileUtils.load(args.head)

    val tokenizedFN = FilenameUtils.removeExtension(args.head) + "_tokenized.txt"

    // check if untokenized file is older for appropriate warning
    val untokFile = new File(args.head)
    val tokFile = new File(tokenizedFN)

    if (untokFile.exists & tokFile.exists & untokFile.lastModified() < tokFile.lastModified()) {
      logger.warn(s"$tokenizedFN is newer than ${args.head}!")
    }
  
    val tokenizedTweetsWithLabels: Seq[(TwitterAccount, String)] = for {
      (account, lbl) <- accounts.toSeq
    } yield {

      // Only English tweets with words
      val englishTweets = account.tweets.filter( t =>
        t.lang != null & t.lang == "en" &
          t.text != null & t.text != ""
      )

      // Filter out stopwords
      val filtered = for {
        t <- englishTweets
      } yield {
        val tt = Tokenizer.annotate(t.text.toLowerCase)
        val ft = filterTags(tt).map(_.token).mkString(" ")
        t.copy(text = ft)
      }

      // Same account but with tokenized tweets
      account.copy(tweets = filtered) -> lbl
    }

    val (tokenizedTweets, labels) = tokenizedTweetsWithLabels.unzip

    FileUtils.saveToFile(tokenizedTweets, labels, tokenizedFN, append = false)
  }
}