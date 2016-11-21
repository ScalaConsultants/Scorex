package io.scalac.elm.state

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
  def empty(keyPairSeed: Array[Byte]): ElmWallet =
    ElmWallet(secret = generateSecret(keyPairSeed))

  def generateSecret(seed: Array[Byte] = Random.randomBytes(PrivKeyLength)): PrivateKey25519 = {
    val pair = Curve25519.createKeyPair(seed)
    PrivateKey25519(pair._1, pair._2)
  }
}

case class ElmWallet(secret: PrivateKey25519,
                     chainTxOutputs: Map[ByteKey, TxOutput] = Map.empty,
                     balance: Long = 0,
                     failedTransactions: List[ElmTransaction] = Nil)
  extends Wallet[PublicKey25519Proposition, ElmTransaction, ElmBlock, ElmWallet] {

  override type S = PrivateKey25519
  override type PI = PublicKey25519Proposition
  override type NVCT = ElmWallet

  private val pubKeyBytes: ByteKey = secret.publicKeyBytes

  def generator: PublicKey25519Proposition = secret.publicImage

  override def scanOffchain(tx: ElmTransaction): ElmWallet = {
    val outs = for {
      txIn <- tx.inputs
      txOut <- chainTxOutputs.get(txIn.closedBoxId)
    } yield txOut

    val sum = outs.map(_.value).sum
    val outIds = outs.map(_.id.key)

    val reducedChainTxOuts = chainTxOutputs -- outIds

    //TODO how to restore balance when transactions are forgotten?
    copy(chainTxOutputs = reducedChainTxOuts, balance = balance - sum)
  }

  def unscanFailed(tx: ElmTransaction, minState: ElmMinState): ElmWallet = {
    val rolledBack = for {
      in <- tx.inputs
      out <- minState.get(in.closedBoxId)
      if out.proposition.pubKeyBytes.key == pubKeyBytes
    } yield out

    val updated = chainTxOutputs ++ rolledBack.map(o => o.id.key -> o)

    copy(chainTxOutputs = updated, balance = updated.values.map(_.value).sum, failedTransactions = tx :: failedTransactions)
  }

  def scanOffchain(txs: Traversable[ElmTransaction]): ElmWallet =
    txs.foldLeft(this)((w, tx) => w.scanOffchain(tx))

  override def scanPersistent(block: ElmBlock): ElmWallet = {
    val income = for {
      tx <- block.txs
      out <- tx.outputs
      if out.proposition.pubKeyBytes.key == pubKeyBytes
    } yield out.id.key -> out


    val outgoings = for {
      tx <- block.txs
      in <- tx.inputs
      out <- chainTxOutputs.get(in.closedBoxId.key)
    } yield out.id.key

    val updatedChainTxOutputs = chainTxOutputs -- outgoings ++ income
    val newBalance = updatedChainTxOutputs.values.map(_.value).sum

    copy(chainTxOutputs = updatedChainTxOutputs, balance = newBalance)
  }

  def accumulatedCoinAge(currentHeight: Int): Long =
    chainTxOutputs.values.map(coinAge(currentHeight)).sum

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
    out.height.map(h => (currentHeight - h + 1) * out.value).getOrElse(0L)




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
