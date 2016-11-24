package io.scalac.elm.state

import io.scalac.elm.transaction.{ElmBlock, ElmTransaction}
import io.scalac.elm.util._
import scorex.core.NodeViewComponentCompanion
import scorex.core.NodeViewModifier.ModifierId
import scorex.core.transaction.MemoryPool

import scala.util.Try

case class ElmMemPool(offchainTxs: Map[ByteKey, ElmTransaction] = Map.empty) extends MemoryPool[ElmTransaction, ElmMemPool] {

  override type NVCT = ElmMemPool

  /**
    * Remove transactions which made it to the chain
    */
  def applyBlock(block: ElmBlock): ElmMemPool =
    ElmMemPool(offchainTxs -- block.txs.drop(1).map(_.id.key))

  /**
    * Add transaction to the pool
    */
  def applyTx(transaction: ElmTransaction): ElmMemPool =
    ElmMemPool(offchainTxs + (transaction.id.key -> transaction))

  /**
    * get IDs from the argument that are not present in the MemPool
    */
  override def notIn(ids: Seq[ModifierId]): Seq[ModifierId] = {
    ids.map(_.key).diff(offchainTxs.keys.toSeq).map(_.array)
  }

  override def getAll(ids: Seq[ModifierId]): Seq[ElmTransaction] = {
    val idSet = ids.map(_.key).toSet
    offchainTxs.filter(kv => idSet(kv._1)).values.toSeq
  }

  def getAll: Iterable[ElmTransaction] = offchainTxs.values

  def merge(other: ElmMemPool): ElmMemPool =
    ElmMemPool(offchainTxs ++ other.offchainTxs)

  def filterValid(minState: ElmMinState): ElmMemPool =
    ElmMemPool(offchainTxs.filter { case (_, tx) => minState.isValid(tx) })


  @deprecated("unnecessary", "")
  override def put(tx: ElmTransaction): Try[ElmMemPool] = ???

  @deprecated("unnecessary", "")
  override def getById(id: ModifierId): Option[ElmTransaction] = ???

  @deprecated("unnecessary", "")
  override def filter(id: Array[Byte]): ElmMemPool = ???

  @deprecated("unnecessary", "")
  override def filter(tx: ElmTransaction): ElmMemPool = filter(Seq(tx))

  @deprecated("unnecessary", "")
  override def filter(txs: Seq[ElmTransaction]): ElmMemPool = ???

  @deprecated("unnecessary", "")
  override def putWithoutCheck(txs: Iterable[ElmTransaction]): ElmMemPool = ???

  @deprecated("unnecessary", "")
  override def put(txs: Iterable[ElmTransaction]): Try[ElmMemPool] = ???

  @deprecated("unnecessary", "")
  override def take(limit: Int): Iterable[ElmTransaction] = ???

  @deprecated("unnecessary", "")
  override def remove(tx: ElmTransaction): ElmMemPool = ???

  @deprecated("unnecessary", "")
  override def companion: NodeViewComponentCompanion = ???
}
