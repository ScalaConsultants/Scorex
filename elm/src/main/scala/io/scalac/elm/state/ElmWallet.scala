package io.scalac.elm.state

import io.scalac.elm.state.ElmWallet._
import io.scalac.elm.transaction.{ElmBlock, ElmTransaction, TxInput, TxOutput}
import io.scalac.elm.util._
import scorex.core.NodeViewComponentCompanion
import scorex.core.transaction.box.Box
import scorex.core.transaction.box.proposition.Constants25519.PrivKeyLength
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.proof.Signature25519
import scorex.core.transaction.state.PrivateKey25519
import scorex.core.transaction.wallet.{Wallet, WalletBox, WalletTransaction}
import scorex.crypto.signatures.Curve25519
import scorex.utils.Random

import scala.util.Try

object ElmWallet {
  def generateSecret(seed: Array[Byte] = Random.randomBytes(PrivKeyLength)): PrivateKey25519 = {
    val pair = Curve25519.createKeyPair(seed)
    PrivateKey25519(pair._1, pair._2)
  }
}

/**
  * @param secret key pair
  * @param chainTxOutputs stores confirmed unspent outputs relevant to this Wallet
  * @param currentBalance sum of confirmed unspent outputs
  */
case class ElmWallet(secret: PrivateKey25519 = generateSecret(),
                     chainTxOutputs: Map[ByteKey, TxOutput] = Map.empty,
                     currentBalance: Long = 0)
  extends Wallet[PublicKey25519Proposition, ElmTransaction, ElmBlock, ElmWallet] {

  override type S = PrivateKey25519
  override type PI = PublicKey25519Proposition
  override type NVCT = ElmWallet

  private val pubKeyBytes: ByteKey = secret.publicKeyBytes


  def generator: PublicKey25519Proposition = secret.publicImage

  def generator: PublicKey25519Proposition = publicKeys.head // our wallet will always have exactly 1 key-pair

  override def scanOffchain(tx: ElmTransaction): ElmWallet = {
    val outs = for {
      txIn <- tx.inputs
      txOut <- chainTxOutputs.get(txIn.closedBoxId)
    } yield txOut

    val sum = outs.map(_.value).sum
    val outIds = outs.map(_.id.key)

    val reducedChainTxOuts = chainTxOutputs -- outIds

    //TODO how to restore balance when transactions are forgotten?
    ElmWallet(secret, reducedChainTxOuts, currentBalance - sum)
  }

  override def scanPersistent(modifier: ElmBlock): ElmWallet = {
    val transactions = modifier.transactions.getOrElse(Nil)

    // increase balance by confirmed outputs
    val increasedWallet = {
      val outs = for {
        tx <- transactions
        out <- tx.outputs if out.proposition.pubKeyBytes.key == pubKeyBytes
      } yield out.id.key -> out

      val increasedOutputs = chainTxOutputs ++ outs
      val sum = outs.map(_._2.value).sum
      ElmWallet(secret, increasedOutputs, currentBalance + sum)
    }

    // if we made coinstake TX remove coinstake inputs
    if (modifier.generator.pubKeyBytes.key == pubKeyBytes) {
      val coinstakeIns = transactions.headOption.toList.flatMap(_.inputs)
      val reducedOutputs = increasedWallet.chainTxOutputs -- coinstakeIns.map(_.closedBoxId.key)
      val sum = coinstakeIns.map(in => increasedWallet.chainTxOutputs(in.closedBoxId.key).value).sum
      ElmWallet(secret, reducedOutputs, increasedWallet.currentBalance - sum)
    }
    else
      increasedWallet
  }

  def accumulatedCoinAge(currentHeight: Int): Long =
    chainTxOutputs.values.map(coinAge(currentHeight)).sum

  //FIXME: there is a possible race condition, the same wallet should not create more than 1 TX
  //FIXME: solution: lock wallet in ElmNodeViewHolder
  def createPayment(payee: PublicKey25519Proposition, amount: Long, fee: Long, currentHeight: Int): Option[ElmTransaction] = {
    //use newest outputs to save coin-age for mining
    val sortedOuts = chainTxOutputs.values.toList.sortBy(coinAge(currentHeight)).reverse

    val requiredAmount = amount + fee
    val requiredOuts = findSufficientOutputs[TxOutput, Long](sortedOuts, _.value, requiredAmount)
    val foundSum = requiredOuts.map(_.value).sum

    if (foundSum < requiredAmount)
      None
    else {
      val inputs = requiredOuts.map { out =>
        val signature = Signature25519(Curve25519.sign(secret.privKeyBytes, out.bytes))
        TxInput(out.id, signature)
      }
      val change = TxOutput(foundSum - requiredAmount, secret.publicImage)
      val toRecipient = TxOutput(amount, payee)
      val outputs = if (change.value > 0) List(toRecipient, change) else List(toRecipient)
      Some(ElmTransaction(inputs, outputs, fee))
    }
  }

  def createCoinstake(targetCoinAge: Long, totalFee: Long, currentHeight: Int): ElmTransaction = {
    val sortedOuts = chainTxOutputs.values.toList.sortBy(coinAge(currentHeight)).reverse
    val selectedOuts = findSufficientOutputs(sortedOuts, coinAge(currentHeight), targetCoinAge)

    val inputs = selectedOuts.map { out =>
      val signature = Signature25519(Curve25519.sign(secret.privKeyBytes, out.bytes))
      TxInput(out.id, signature)
    }

    val outputs =
      TxOutput(selectedOuts.map(_.value).sum, secret.publicImage) ::
        TxOutput(totalFee, secret.publicImage) :: Nil

    ElmTransaction(inputs, outputs, 0)
  }

  private def findSufficientOutputs[T, N : Numeric](outs: List[T], extract: T => N, value: N): List[T] = {
    val num = implicitly[Numeric[N]]
    if (num.gt(value, num.zero)) {
      outs match {
        case hd :: tl => hd :: findSufficientOutputs(tl, extract, num.minus(value, extract(hd)))
        case Nil => Nil
      }
    } else Nil
  }

  private def coinAge(currentHeight: Int)(out: TxOutput): Long =
    out.height.map(h => (currentHeight - h) * out.value).getOrElse(0L)




  @deprecated("unnecessary", "")
  override def secretByPublicImage(publicImage: PI): Option[S] = ???

  @deprecated("unnecessary", "")
  override def generateNewSecret(): ElmWallet = this

  @deprecated("unnecessary", "")
  override def secrets: Set[S] = Set(secret)

  @deprecated("unnecessary", "")
  override def rollback(to: VersionTag): Try[ElmWallet] = ???

  @deprecated("unnecessary", "")
  override def publicKeys: Set[PublicKey25519Proposition] = Set(secret.publicImage)

  @deprecated("unnecessary", "")
  override def companion: NodeViewComponentCompanion = ???

  @deprecated("unnecessary", "")
  override def boxes(): Seq[WalletBox[PublicKey25519Proposition, _ <: Box[PublicKey25519Proposition]]] = ???

  @deprecated("unnecessary", "")
  override def scanOffchain(txs: Seq[ElmTransaction]): ElmWallet =
    txs.foldLeft(this)((w, tx) => w.scanOffchain(tx))

  @deprecated("unnecessary", "")
  override def historyTransactions: Seq[WalletTransaction[PublicKey25519Proposition, ElmTransaction]] = ???

}
