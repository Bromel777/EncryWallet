package utils

import scala.io.Source
import org.encryfoundation.common.Algos
import scodec.bits.BitVector

object Mnemonic {

  private def getWords(language: String = "english"): Array[String] =
    Source.fromFile("dist/languages/" + language + "/words.txt").getLines.toArray

  def seedFromMnemonic(mnemonicCode: String, passPhrase: String = ""): Array[Byte] =
    Algos.hash(mnemonicCode + "mnemonic=" + passPhrase)

  def entropyToMnemonicCode(entropy: Array[Byte]): String = {
    val words: Array[String] = getWords()
    val checkSum: BitVector = BitVector(Algos.hash(entropy))
    val entropyWithCheckSum: BitVector = BitVector(entropy) ++ checkSum.take(4)

    entropyWithCheckSum.grouped(11).map { i =>
      words(i.toInt(signed = false))
    }.mkString(" ")
  }

}
