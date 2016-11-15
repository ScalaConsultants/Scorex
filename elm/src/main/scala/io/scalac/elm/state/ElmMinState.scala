package io.scalac.elm.state

import io.scalac.elm.transaction.{ElmBlock, ElmTransaction, TxOutput}
import io.scalac.elm.util.ByteKey
import scorex.core.NodeViewComponentCompanion
import scorex.core.block.StateChanges
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.MinimalState
import scorex.core.transaction.state.MinimalState.VersionTag
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58
import scorex.crypto.signatures.Curve25519

import scala.util.{Failure, Try}


object ElmMinState {
  val EmptyVersion: Array[Byte] = Array.fill(32)(0: Byte)
}

case class ElmMinState(storage: Map[ByteKey, TxOutput] = Map())
  extends ScorexLogging
  with MinimalState[PublicKey25519Proposition, TxOutput, ElmTransaction, ElmBlock, ElmMinState] {

  def isEmpty: Boolean = storage.isEmpty

  override def toString: String = {
    s"SimpleState at ${Base58.encode(version)}\n" + storage.keySet.flatMap(k => storage.get(k)).mkString("\n  ")
  }

  override def closedBox(boxId: Array[Byte]): Option[TxOutput] =
    storage.get(boxId)

  override def rollbackTo(version: VersionTag): Try[ElmMinState] = {
    log.warn("Rollback is not implemented")
    Try(this)
  }

  override def applyChanges(change: StateChanges[PublicKey25519Proposition, TxOutput], newVersion: VersionTag): Try[ElmMinState] = Try {
    val toRemove = change.boxIdsToRemove.map(_.key)
    val toAppend = change.toAppend.map(out => out.id.key -> out)
    ElmMinState(storage -- toRemove ++ toAppend)
  }

  override def companion: NodeViewComponentCompanion = ???

  override type NVCT = ElmMinState

  override def validate(tx: ElmTransaction): Try[Unit] = Try {
    val inputSum = tx.inputs.flatMap(in => storage.get(in.closedBoxId)).map(_.value).sum
    val outputSum = tx.outputs.map(_.value).sum + tx.fee

    val addsUp = inputSum == outputSum

    lazy val positiveOuts = tx.outputs.forall(_.value > 0)

    lazy val signed = tx.inputs.forall { in =>
      val out = storage.get(in.closedBoxId)
      out.map(o => Curve25519.verify(in.boxKey.signature, o.bytes, o.proposition.pubKeyBytes)).exists(identity)
    }

    val result = addsUp && positiveOuts && signed

    if (!result) {
      log.warn(s"Transaction ${tx.id.base58} did not validate: addsUp==$addsUp, positiveOuts==$positiveOuts, signed==$signed")
    }

    result
  } .filter(identity).map(_ => ())
    .recoverWith{case _ => Failure(new Exception(s"Transaction failed validation"))}

  def validateBlock(block: ElmBlock): Try[Unit] = Try {
    //validate against double spending, other block validations should be done at blockchain level
    val outputIds = block.transactions.toSeq.flatten.flatMap(_.inputs).map(_.closedBoxId.key)
    outputIds.toSet.size == outputIds.size
  } .filter(identity).map(_ => ())
    .recoverWith{case _ => Failure(new Exception(s"Block failed validation"))}

  override def changes(block: ElmBlock): Try[StateChanges[PublicKey25519Proposition, TxOutput]] = Try {
    val (toRemove, toAppend) = block.transactions.getOrElse(Nil).map { tx =>
      val txToRemove = tx.inputs.map(_.closedBoxId).toSet
      val txToAppend = tx.outputs.toSet
      txToRemove -> txToAppend
    }.toSet.unzip

    StateChanges(toRemove.flatten, toAppend.flatten)
  }

  // Not used
  override def version: VersionTag = ElmMinState.EmptyVersion

  override def boxesOf(proposition: PublicKey25519Proposition): Seq[TxOutput] =
    storage.values.filter(_.proposition.address == proposition.address).toSeq
}