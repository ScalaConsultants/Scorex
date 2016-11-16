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

  case class TimedTxOutput(out: TxOutput, timestamp: Long) {
    def coinAge(currentTime: Long) = BigInt(currentTime - timestamp) * out.value
  }

  //TODO: config
  val baseFee = 10L
}

/**
  *
  * @param secret key pair
  * @param chainTxOutputs stores confirmed unspent outputs relevant to this Wallet
  * @param currentBalance sum of confirmed unspent outputs
  */
case class ElmWallet(secret: PrivateKey25519 = generateSecret(),
                     chainTxOutputs: Map[ByteKey, TimedTxOutput] = Map(),
                     currentBalance: Long = 0)
  extends Wallet[PublicKey25519Proposition, ElmTransaction, ElmBlock, ElmWallet] {

  override type S = PrivateKey25519
  override type PI = PublicKey25519Proposition

  override type NVCT = ElmWallet

  private val pubKeyBytes: ByteKey = secret.publicKeyBytes

  override def secretByPublicImage(publicImage: PI): Option[S] = {
    if (publicImage.address == secret.publicImage.address) Some(secret) else None
  }

  /**
    * Only one secret is supported so this method always returns unmodified wallet
    */
  override def generateNewSecret(): ElmWallet = this

  override def secrets: Set[S] = Set(secret)

  override def rollback(to: VersionTag): Try[ElmWallet] = ???

  override def publicKeys: Set[PublicKey25519Proposition] = Set(secret.publicImage)

  def generator: PublicKey25519Proposition = publicKeys.head // our wallet will always have exactly 1 key-pair

  override def scanOffchain(tx: ElmTransaction): ElmWallet = {
    val outs = for {
      txIn <- tx.inputs
      txOut <- chainTxOutputs.get(txIn.closedBoxId)
    } yield txOut.out

    val sum = outs.map(_.value).sum
    val outIds = outs.map(_.id.key)

    val reducedChainTxOuts = chainTxOutputs -- outIds

    //TODO how to restore balance when transactions are forgotten?
    ElmWallet(secret, reducedChainTxOuts, currentBalance - sum)
  }

  override def scanOffchain(txs: Seq[ElmTransaction]): ElmWallet =
    txs.foldLeft(this)((w, tx) => w.scanOffchain(tx))

  override def historyTransactions: Seq[WalletTransaction[PublicKey25519Proposition, ElmTransaction]] = ???

  override def scanPersistent(modifier: ElmBlock): ElmWallet = {
    val transactions = modifier.transactions.getOrElse(Nil)

    // increase balance by confirmed outputs
    val increasedWallet = {
      val outs = for {
        tx <- transactions
        out <- tx.outputs if out.proposition.pubKeyBytes.key == pubKeyBytes
      } yield out.id.key -> TimedTxOutput(out, tx.timestamp)

      val increasedOutputs = chainTxOutputs ++ outs
      val sum = outs.map(_._2.out.value).sum
      ElmWallet(secret, increasedOutputs, currentBalance + sum)
    }

    // if we made coinstake TX remove coinstake inputs
    if (modifier.generator.pubKeyBytes.key == pubKeyBytes) {
      val coinstakeIns = transactions.headOption.toList.flatMap(_.inputs)
      val reducedOutputs = increasedWallet.chainTxOutputs -- coinstakeIns.map(_.closedBoxId.key)
      val sum = coinstakeIns.map(in => increasedWallet.chainTxOutputs(in.closedBoxId.key).out.value).sum
      ElmWallet(secret, reducedOutputs, increasedWallet.currentBalance - sum)
    }
    else
      increasedWallet
  }

  override def companion: NodeViewComponentCompanion = ???

  override def boxes(): Seq[WalletBox[PublicKey25519Proposition, _ <: Box[PublicKey25519Proposition]]] = ???

  def accumulatedCoinAge: BigInt =
    chainTxOutputs.values.map(coinAge(System.currentTimeMillis)).sum

  //FIXME: there is a possible race condition, the same wallet should not create more than 1 TX
  def createPayment(to: PublicKey25519Proposition, amount: Long, priority: Int): Option[ElmTransaction] = {
    //use newest outputs to save coin-age for mining

    val currentTime = System.currentTimeMillis()
    val sortedOuts = chainTxOutputs.values.toList.sortBy(coinAge(currentTime)).reverse.map(_.out)
    val fee = priority * baseFee
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
      val toRecipient = TxOutput(amount, to)
      val outputs = if (change.value > 0) List(toRecipient, change) else List(toRecipient)
      Some(ElmTransaction(inputs, outputs, fee, currentTime))
    }
  }

  def createCoinstake(targetCoinAge: BigInt, totalFee: Long): ElmTransaction = {
    val now = System.currentTimeMillis
    val sortedOuts = chainTxOutputs.values.toList.sortBy(coinAge(now)).reverse
    val selectedOuts = findSufficientOutputs[TimedTxOutput, BigInt](sortedOuts, coinAge(now), targetCoinAge)

    val inputs = selectedOuts.map { to =>
      val signature = Signature25519(Curve25519.sign(secret.privKeyBytes, to.out.bytes))
      TxInput(to.out.id, signature)
    }

    val outputs =
      TxOutput(selectedOuts.map(_.out.value).sum, secret.publicImage) ::
      TxOutput(totalFee, secret.publicImage) :: Nil

    ElmTransaction(inputs, outputs, 0, now)
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

  private def coinAge(currentTime: Long)(out: TimedTxOutput): BigInt =
    out.coinAge(currentTime)
}
