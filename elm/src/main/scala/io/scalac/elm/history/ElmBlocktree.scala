package io.scalac.elm.history

import cats.data.Xor
import io.scalac.elm.config.ElmConfig.ConsensusConf
import io.scalac.elm.history.ElmBlocktree._
import io.scalac.elm.state.ElmMinState
import io.scalac.elm.transaction.{ElmBlock, ElmTransaction, TxInput, TxOutput}
import io.scalac.elm.util.{ByteKey, Error}
import scorex.core.NodeViewComponentCompanion
import scorex.core.consensus.BlockChain
import scorex.core.consensus.History.{BlockId, HistoryComparisonResult, RollbackTo}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.crypto.signatures.Curve25519
import scorex.crypto.signatures.SigningFunctions._

import scala.annotation.tailrec
import scala.util.Try

object ElmBlocktree {
  case class Node(block: ElmBlock, children: Set[ByteKey], height: Int, score: Long, accumulatedScore: Long) {
    val id: ByteKey = block.id.key
    val parentId: ByteKey = block.parentId.key
    def addChild(childId: ByteKey): Node = copy(children = children + childId)
    def removeChild(childId: ByteKey): Node = copy(children = children - childId)
  }

  /**
    * We need a deterministic method of designating the main chain, especially when 2 different chains have the same score.
    * Choosing the block ID as a tie-breaker is of course very naive, as nodes would then be incentivized to manually pick lowest block IDs,
    * resulting in conflicts. For now we just assume they won't do that.
    */
  implicit def nodeOrdering: Ordering[Node] = Ordering.by(n => (n.accumulatedScore, n.id.base58))

  def zero(consensusConf: ConsensusConf): ElmBlocktree = {
    val zeroNode = Node(ElmBlock.zero, Set.empty, 0, 0, 0)
    ElmBlocktree(Map(zeroNode.id -> zeroNode), Set(zeroNode.id), consensusConf)
  }

  case object BlockValidationError extends Error
}

case class ElmBlocktree private(
  blocks: Map[ByteKey, Node],
  leaves: Set[ByteKey],
  consensusConf: ConsensusConf
) extends BlockChain[PublicKey25519Proposition, ElmTransaction, ElmBlock, ElmSyncInfo, ElmBlocktree] {

  override type NVCT = ElmBlocktree

  def append(block: ElmBlock, minState: ElmMinState): Error Xor ElmBlocktree = {
    if (isValid(block, minState)) {
      val blockId = block.id.key
      val parentId = block.parentId.key
      val parent = blocks(parentId)

      val score = calculateScore(block, parent.height, minState)

      val newNode = Node(block, Set.empty, parent.height + 1, score, parent.accumulatedScore + score)
      val updatedParent = parent.addChild(blockId)

      val updatedBlocks = blocks + (parentId -> updatedParent) + (blockId -> newNode)
      val updatedLeaves = leaves - parentId + blockId
      val updatedTree = ElmBlocktree(updatedBlocks, updatedLeaves, consensusConf)

      val limitedTree = limitBranches(consensusConf.N, updatedTree)
      Xor.right(limitedTree)
    } else {
      Xor.left(BlockValidationError)
    }
  }

  def calculateScore(block: ElmBlock, parentHeight: Int, minState: ElmMinState): Long = {
    val coinstake = block.txs.head
    val partialScores = for {
      in <- coinstake.inputs
      txOut <- minState.get(in.closedBoxId)
      height <- txOut.height
    } yield txOut.value * (parentHeight - height)

    partialScores.sum
  }

  def mainChain: Stream[Node] =
    chainOf(maxLeaf.id)

  override lazy val height: Int = maxLeaf.height

  /**
    * Traverse the tree from leaf to root to return a single chain (branch)
    */
  def chainOf(blockId: ByteKey): Stream[Node] = {
    blocks.get(blockId).filter(_ != ElmBlock.zero.id.key)
      .map(node => node #:: chainOf(node.parentId)).getOrElse(Stream.Empty)
  }

  /**
    * Select a single chain by score
    * @param n chain order: 1 - main chain, 2 - next best chain, and so on...
    */
  def chainOf(n: Int): Option[Stream[Node]] =
    sortedLeaves.lift(n - 1).map(chainOf)

  def sortedLeaves: List[ByteKey] =
    leaves.map(blocks).toList.sorted.reverse.map(_.id)

  override def blockById(blockId: BlockId): Option[ElmBlock] =
    blocks.get(blockId).map(_.block)

  override def contains(id: BlockId): Boolean =
    blocks.contains(id.key)

  /**
    * Compare history:
    *   Equal   - if leaves are the same
    *   Older   - if all the leaves of this blocktree are nodes of the remote blocktree
    *   Younger - otherwise
    *
    * This means two blocktrees could be mutually Younger. Synchronization has to support that.
    */
  override def compare(other: ElmSyncInfo): HistoryComparisonResult.Value = {
    import HistoryComparisonResult._

    if (leaves == other.leaves)
      Equal
    else if (leaves.forall(other.blocks))
      Older
    else
      Younger
  }

  override def syncInfo(answer: Boolean): ElmSyncInfo =
    ElmSyncInfo(answer, leaves, blocks.keySet)

  def continuationIds(syncInfo: ElmSyncInfo): List[ByteKey] =
    findContinuations(findStartingPoints(blocks.keySet, syncInfo.blocks))

  override def applicable(block: ElmBlock): Boolean =
    blocks.contains(block.parentId) && !blocks.contains(block.id)

  /**
    * Find a leaf with the lowest score
    */
  def minLeaf: Node =
    leaves.map(blocks).min

  /**
    * Find a leaf with the highest score - the last block of the main chain
    */
  def maxLeaf: Node =
    leaves.map(blocks).max

  /**
    * Target score for given chain. Currently constant
    */
  def targetScore(leafId: ByteKey): Long = consensusConf.baseTarget

  /**
    * Limits the branches of the blocktree to a maximum of n
    * if there are more than n branches, the lowest-scored ones are removed
    */
  @tailrec
  private def limitBranches(n: Int, tree: ElmBlocktree): ElmBlocktree = {
    if (leaves.size <= n)
      tree
    else {
      val updatedTree = removeBranch(minLeaf.id, tree)
      limitBranches(n, updatedTree)
    }
  }

  /**
    * Removes a branch given its leaf ID. If there's only one branch it won't be removed.
    */
  @tailrec
  private def removeBranch(leafId: ByteKey, tree: ElmBlocktree): ElmBlocktree = {
    if (tree.leaves.size == 1)
      tree
    else {
      val leaf = blocks(leafId)
      val parent = blocks(leaf.block.parentId.key)
      val updatedParent = parent.removeChild(leafId)
      val updatedTree = ElmBlocktree(blocks - leafId, tree.leaves - leafId, consensusConf)

      if (updatedParent.children.nonEmpty)
        updatedTree
      else
        removeBranch(parent.id, updatedTree)
    }
  }

  private def isValid(block: ElmBlock, minState: ElmMinState): Boolean =
    applicable(block) && {
      val parent = blocks(block.parentId)

      val genesis = parent.height == 0

      val signed = {
        val generatorPubKey = block.generator.pubKeyBytes
        val signature = block.generationSignature
        val message = block.copy(generationSignature = Array()).bytes
        Curve25519.verify(signature, message, generatorPubKey)
      }

      val coinstakeValid = {
        val totalFees = block.txs.map(_.fee).sum
        block.txs.headOption.map(minState.isCoinstakeValid(_, totalFees)).exists(identity)
      }

      val transactionsValid = {
        val txs = block.txs.drop(1)
        txs.nonEmpty && txs.forall(minState.isValid)
      }

      val correctHeights = block.txs.flatMap(_.outputs).flatMap(_.height).forall(_ == parent.height + 1)

      val uniqueInputs = {
        val outIds = block.txs.flatMap(_.inputs).map(_.closedBoxId.key)
        outIds.toSet.size == outIds.size
      }

      genesis || (
        signed && coinstakeValid && transactionsValid && correctHeights && uniqueInputs
      )
    }

  private def findStartingPoints(theseBlocks: Set[ByteKey], otherBlocks: Set[ByteKey]): List[ByteKey] =
    if (theseBlocks.isEmpty) Nil else {
      val diff = theseBlocks.diff(otherBlocks)
      val parents = diff.map(blocks).map(_.parentId)
      val found = parents.intersect(otherBlocks)
      val deeper = parents.diff(found)
      found.toList ::: findStartingPoints(deeper, otherBlocks)
    }

  private def findContinuations(nodeIds: List[ByteKey]): List[ByteKey] = {
    val directChildren = for {
      nodeId <- nodeIds
      node <- blocks.get(nodeId).toList
      childId <- node.children
    } yield childId

    if (directChildren.isEmpty)
      Nil
    else
      directChildren ::: findContinuations(directChildren)
  }




  // Unused methods:

  @deprecated("we need a mempool of unused transactions", "")
  override def append(block: ElmBlock): Try[(ElmBlocktree, Option[RollbackTo[ElmBlock]])] = ???

  @deprecated("unused: cannot calculate score without minstate", "")
  override def score(block: ElmBlock): BigInt = 0

  @deprecated("unnecessary", "")
  override def chainScore(): BigInt = maxLeaf.accumulatedScore

  @deprecated("unused method (actually the place of usage is unused)", "")
  override def companion: NodeViewComponentCompanion = ???

  @deprecated("unnecessary", "")
  override def heightOf(blockId: BlockId): Option[Int] =
    blocks.get(blockId).map(_.height)

  @deprecated("unnecessary", "")
  override def discardBlock(): Try[ElmBlocktree] = ???

  @deprecated("unnecessary", "")
  override def blockAt(height: Int): Option[ElmBlock] = None

  @deprecated("unnecessary", "")
  override def children(blockId: BlockId): Seq[ElmBlock] = Nil
}
