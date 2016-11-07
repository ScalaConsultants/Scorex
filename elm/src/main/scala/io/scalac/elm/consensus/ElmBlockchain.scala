package io.scalac.elm.consensus

import io.scalac.elm.consensus.ElmBlockchain.Height
import io.scalac.elm.transaction.{ElmBlock, ElmTransaction}
import io.scalac.elm.util.ByteKey
import scorex.core.NodeViewComponentCompanion
import scorex.core.consensus.BlockChain
import scorex.core.consensus.History.{BlockId, HistoryComparisonResult, RollbackTo}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.crypto.encode.Base58

import scala.util.{Failure, Success, Try}

object ElmBlockchain {
  type Height = Int
}

case class ElmBlockchain(blockIds: Map[Height, BlockId] = Map(), blocks: Map[ByteKey, ElmBlock] = Map())
  extends BlockChain[PublicKey25519Proposition, ElmTransaction, ElmBlock, ElmSyncInfo, ElmBlockchain] {

  import BlockChain.Score

  def lastBlockId: BlockId = blockIds(height())

  override def isEmpty: Boolean = blocks.isEmpty

  override def blockById(blockId: BlockId): Option[ElmBlock] =
    blocks.get(blockId)

  override def append(block: ElmBlock): Try[(ElmBlockchain, Option[RollbackTo[ElmBlock]])] = {
    val blockId = block.id
    val parentId = block.parentId

    if (isEmpty || (lastBlock.id.key == parentId.key)) {
      val h = height() + 1
      val newChain = ElmBlockchain(blockIds + (h -> blockId), blocks + (blockId.key -> block))
      Success(newChain, None)
    } else Failure(new Exception(s"Last block id is ${Base58.encode(blockIds.last._2)}, " +
      s"expected ${Base58.encode(parentId)}}"))
  }

  override def chainScore(): BigInt = blocks.values.map(score).sum

  override type NVCT = ElmBlockchain

  override def companion: NodeViewComponentCompanion = ???

  // FIXME: MinimalState is needed to calculate score efficiently (but also need the timestamps)
  // FIXME: what about genesis block (height = 2)?
  override def score(block: ElmBlock): Score = {
    val now = System.currentTimeMillis() //hmmm, synchronization?
    val allTxs = blocks.values.flatMap(_.transactions.getOrElse(Nil))
    val allOuts = allTxs.flatMap(tx => tx.outputs.map(tx.timestamp -> _)).map(tOut => tOut._2.id.key -> tOut).toMap
    val coinstakeIns = block.transactions.getOrElse(Nil).headOption.map(_.inputs).getOrElse(Nil)
    coinstakeIns.flatMap(in => allOuts.get(in.closedBoxId)).map { case (t, out) => (now - t) * out.value }.sum
  }

  /**
    * Height of the a chain, or a longest chain in an explicit block-tree
    */
  override def height(): Height = blocks.size

  override def heightOf(blockId: BlockId): Option[Height] =
    blockIds.find(_._2.key == blockId.key).map(_._1)

  override def discardBlock(): Try[ElmBlockchain] = ???

  override def blockAt(height: Height): Option[ElmBlock] =
    blockIds.get(height).map(_.key).flatMap(blocks.get)

  override def children(blockId: BlockId): Seq[ElmBlock] =
    heightOf(blockId).map(_ + 1).flatMap(blockAt).toSeq

  //FIXME: Not sure whether to use block IDs or score
  override def compare(other: ElmSyncInfo): HistoryComparisonResult.Value = {
    import HistoryComparisonResult._
    log.info(s"Comparing blockchains:" +
      s"\n\tlocal:  score=${chainScore()}, lastblockId=${Base58.encode(lastBlockId)}" +
      s"\n\tremote: score=${other.score}, lastblockId=${Base58.encode(other.lastBlockId)}")
    blocks.get(other.lastBlockId).map { block =>
      if (block.id.key == lastBlockId.key) Equal else Younger
    }.getOrElse(Older)
//    val local = chainScore()
//    val remote = other.score
//    if (local < remote) Older
//    else if (local == remote) Equal
//    else Younger
  }

  override def syncInfo(answer: Boolean): ElmSyncInfo =
    ElmSyncInfo(answer, lastBlockId, chainScore())
}
