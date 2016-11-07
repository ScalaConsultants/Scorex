package io.scalac.elm.state

import io.scalac.elm.transaction.ElmTransaction
import io.scalac.elm.util.ByteKey
import scorex.core.NodeViewComponentCompanion
import scorex.core.NodeViewModifier.ModifierId
import scorex.core.transaction.MemoryPool

import scala.collection.concurrent.TrieMap
import scala.util.Try

class ElmMemPool extends MemoryPool[ElmTransaction, ElmMemPool] {

  private val unconfTxs: TrieMap[ByteKey, ElmTransaction] = TrieMap()

  //getters
  override def getById(id: ModifierId): Option[ElmTransaction] = unconfTxs.get(id)

  override def filter(id: Array[Byte]): ElmMemPool = {
    unconfTxs.remove(id)
    this
  }

  override def filter(tx: ElmTransaction): ElmMemPool = filter(Seq(tx))

  override def filter(txs: Seq[ElmTransaction]): ElmMemPool = {
    txs.foreach(tx => unconfTxs.remove(tx.id))
    this
  }

  override def putWithoutCheck(txs: Iterable[ElmTransaction]): ElmMemPool = {
    txs.foreach(tx => unconfTxs.put(tx.id, tx))
    this
  }

  //modifiers
  override def put(tx: ElmTransaction): Try[ElmMemPool] = put(Seq(tx))

  override def put(txs: Iterable[ElmTransaction]): Try[ElmMemPool] = Try {
    txs.foreach(tx => require(!unconfTxs.contains(tx.id)))
    putWithoutCheck(txs)
  }

  override def take(limit: Int): Iterable[ElmTransaction] =
    unconfTxs.keys.take(limit).flatMap(k => unconfTxs.get(k))

  override def remove(tx: ElmTransaction): ElmMemPool = filter(tx)

  //get mempool transaction ids not presenting in ids
  override def notIn(ids: Seq[ModifierId]): Seq[ModifierId] = {
    unconfTxs.filter { case (id, tx) =>
      !ids.exists(_.key == id)
    }.keySet.map(_.array).toSeq
  }

  override def getAll(ids: Seq[ModifierId]): Seq[ElmTransaction] = {
    val idSet = ids.map(_.key).toSet
    unconfTxs.filter(kv => idSet(kv._1)).values.toSeq
  }

  def getAll: Seq[ElmTransaction] = unconfTxs.values.toSeq

  override def companion: NodeViewComponentCompanion = ???

  override type NVCT = ElmMemPool
}
