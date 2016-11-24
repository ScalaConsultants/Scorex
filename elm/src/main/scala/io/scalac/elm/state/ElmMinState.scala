package io.scalac.elm.state

import io.scalac.elm.transaction.{ElmBlock, ElmTransaction, TxOutput}
import io.scalac.elm.util.ByteKey
import scorex.core.NodeViewComponentCompanion
import scorex.core.block.StateChanges
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.MinimalState
import scorex.core.transaction.state.MinimalState.VersionTag
import scorex.core.utils.ScorexLogging
import scorex.crypto.signatures.Curve25519

import scala.util.Try

object ElmMinState {

}

case class ElmMinState(storage: Map[ByteKey, TxOutput] = Map.empty)
  extends ScorexLogging
  with MinimalState[PublicKey25519Proposition, TxOutput, ElmTransaction, ElmBlock, ElmMinState] {

  override type NVCT = ElmMinState

  def applyBlock(block: ElmBlock): ElmMinState = {
    val (toRemove, toAdd): (List[ByteKey], List[(ByteKey, TxOutput)]) =
      block.txs.foldLeft(List.empty[ByteKey] -> List.empty[(ByteKey, TxOutput)]) {
        case ((rem, add), tx) =>
          (tx.inputs.map(_.closedBoxId.key) ::: rem) -> (tx.outputs.map(o => o.id.key -> o) ::: add)
      }

    ElmMinState(storage -- toRemove ++ toAdd)
  }

  override def isValid(tx: ElmTransaction): Boolean = {
    val addsUp = {
      val inputSum = tx.inputs.flatMap(in => get(in.closedBoxId)).map(_.value).sum
      val outputSum = tx.outputs.map(_.value).sum + tx.fee
      inputSum == outputSum
    }

    val positiveOuts = tx.outputs.forall(_.value > 0)

    val positiveFee = tx.fee > 0

    val signed = tx.inputs.forall { in =>
      val out = get(in.closedBoxId)
      out.map(o => Curve25519.verify(in.boxKey.signature, o.bytes, o.proposition.pubKeyBytes)).exists(identity)
    }

    addsUp && positiveOuts && positiveFee && signed
  }

  def isCoinstakeValid(tx: ElmTransaction, totalFees: Long): Boolean = {
    val addsUp = {
      val inputSum = tx.inputs.flatMap(in => get(in.closedBoxId)).map(_.value).sum
      val outputSum = tx.outputs.map(_.value).sum

      inputSum + totalFees == outputSum
    }

    val positiveOuts = tx.outputs.forall(_.value > 0)

    val zeroFee = tx.fee == 0

    val signed = tx.inputs.forall { in =>
      val out = get(in.closedBoxId)
      out.map(o => Curve25519.verify(in.boxKey.signature, o.bytes, o.proposition.pubKeyBytes)).exists(identity)
    }

    addsUp && positiveOuts && zeroFee && signed
  }

  def get(outId: ByteKey): Option[TxOutput] =
    storage.get(outId)



  @deprecated("unnecessary", "")
  override def closedBox(boxId: Array[Byte]): Option[TxOutput] =
    storage.get(boxId)

  @deprecated("unnecessary", "")
  override def rollbackTo(version: VersionTag): Try[ElmMinState] = ???

  @deprecated("unnecessary", "")
  override def applyChanges(change: StateChanges[PublicKey25519Proposition, TxOutput], newVersion: VersionTag): Try[ElmMinState] = ???

  @deprecated("unnecessary", "")
  override def companion: NodeViewComponentCompanion = ???

  @deprecated("unnecessary", "")
  override def validate(tx: ElmTransaction): Try[Unit] = ???

  @deprecated("unnecessary", "")
  override def changes(block: ElmBlock): Try[StateChanges[PublicKey25519Proposition, TxOutput]] = ???

  @deprecated("unnecessary", "")
  override def version: VersionTag = ???

  @deprecated("unnecessary", "")
  override def boxesOf(proposition: PublicKey25519Proposition): Seq[TxOutput] = ???

}
