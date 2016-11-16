package io.scalac.elm.history

import io.scalac.elm.history.ElmBlocktree.{FullState, Node}
import io.scalac.elm.state.{ElmMemPool, ElmMinState, ElmWallet}
import io.scalac.elm.transaction.{ElmBlock, ElmTransaction}
import io.scalac.elm.util.ByteKey
import scorex.core.NodeViewComponentCompanion
import scorex.core.consensus.BlockChain
import scorex.core.consensus.History.{BlockId, HistoryComparisonResult, RollbackTo}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging

import scala.annotation.tailrec
import scala.util.Try

object ElmBlocktree {
  case class Node(block: ElmBlock, children: Set[ByteKey], height: Int, score: Long, accumulatedScore: Long) {
    def id: ByteKey = block.id.key
    def addChild(childId: ByteKey): Node = copy(children = children + childId)
    def removeChild(childId: ByteKey): Node = copy(children = children - childId)
  }

  /**
    * In order to main multiple blockchains we need a quick way determinig the state for each block in the tree.
    * While MemPool is not directly related to the chain, it's convenient to store it here in case the Forger,
    * does not use all avaialable transactions. When forging a new block, the Forger should merge the main MemPool with
    * the one stored on the parent block.
    *
    * I don't think this is a great design. Fristly, becaus the blockchain is storing that state, secondly because it creates
    * cyclic dependencies between packages. But at this time it seems the most convenient.
    */
  case class FullState(minState: ElmMinState, wallet: ElmWallet, memPool: ElmMemPool)

  val zero: ElmBlocktree = {
    val zeroNode = Node(ElmBlock.zero, Set.empty, 0, 0, 0)
    val zeroState = FullState(ElmMinState(), ElmWallet(), new ElmMemPool)
    ElmBlocktree(Map(zeroNode.id -> zeroNode), Set(zeroNode.id), Map(zeroNode.id -> zeroState))
  }
}

case class ElmBlocktree private(
  blocks: Map[ByteKey, Node] = Map(),
  leaves: Set[ByteKey],
  state: Map[ByteKey, FullState]
) extends BlockChain[PublicKey25519Proposition, ElmTransaction, ElmBlock, ElmSyncInfo, ElmBlocktree] with ScorexLogging {

  override type NVCT = ElmBlocktree

  //TODO: configure
  val N = 8
  val confirmationDepth = 5

  def append(block: ElmBlock, unusedTxs: ElmMemPool): ElmBlocktree = {
    val blockId = block.id.key
    val parentId = block.parentId.key

    if (leaves(parentId)) {
      val score = calculateScore(block, state(parentId).minState)
      val parent = blocks(parentId)
      val updatedParent = parent.addChild(blockId)
      val newNode = Node(block, Set.empty, parent.height + 1, score, parent.accumulatedScore + score)

      val newMinState = state(parentId).minState.applyModifier(block).get // block should be validated before
      val newWallet = state(parentId).wallet.scanPersistent(block)
      val fullState = FullState(newMinState, newWallet, unusedTxs)

      val updatedBlocks = blocks + (parentId -> updatedParent) + (blockId -> newNode)
      val updatedTree = ElmBlocktree(updatedBlocks, leaves + blockId, state + (blockId -> fullState))
      val limitedTree = limitBranches(N, updatedTree)
      limitedTree
    } else {
      log.error(s"Parent ID [${parentId.base58}] of new block [${blockId.base58}] was not found among the leaves")
      this
    }
  }

  def calculateScore(block: ElmBlock, minState: ElmMinState): Long = {
    val coinstake = block.txs.head
    val partialScores = for {
      in <- coinstake.inputs
      txOut <- minState.get(in.closedBoxId)
      depth <- txOut.depth
    } yield txOut.value * depth

    partialScores.sum
  }

  /**
    * FullState of the main chain
    */
  def fullState: FullState =
    state(maxLeaf.id)

  def mainChain: List[Node] =
    chainOf(maxLeaf.id)

  def chainOf(blockId: ByteKey): List[Node] =
    blocks.get(blockId).filter(_ != ElmBlock.zero.id.key)
      .map(node => node :: chainOf(node.id)).getOrElse(Nil)

  override def blockById(blockId: BlockId): Option[ElmBlock] =
    blocks.get(blockId).map(_.block)

  /**
    * Compare history by blocktree leaves. If the other blocktree has leaves that do not exist anywhere in this blocktree,
    * that doesn't mean that blocktree cointains all the nodes of this blocktree. So 2 blocktree could be mutually Older.
    * I think that should be OK.
    */
  override def compare(other: ElmSyncInfo): HistoryComparisonResult.Value = {
    import HistoryComparisonResult._

    val otherLeaves = other.startingPoints.map(_._2.key).toSet

    if (leaves == otherLeaves)
      Equal
    else if (otherLeaves.forall(blocks.contains))
      Younger
    else
      Older
  }

  override def syncInfo(answer: Boolean): ElmSyncInfo =
    ElmSyncInfo(answer, leaves.toList.map(_.array))

  /**
    * Find a leaf with the lowest score
    */
  private def minLeaf: Node =
    leaves.map(blocks).minBy(_.accumulatedScore)

  /**
    * Find a leaf with the highest score - the last block of the main chain
    */
  private def maxLeaf: Node =
    leaves.map(blocks).maxBy(_.accumulatedScore)

  /**
    * Limits the branches of the blocktree to a maximum of n
    * if there are more than n branches, the lowest-scored ones are removed
    */
  @tailrec
  private def limitBranches(n: Int, tree: ElmBlocktree): ElmBlocktree = {
    if (leaves.size <= n)
      this
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
      val updatedTree = ElmBlocktree(blocks - leafId, tree.leaves - leafId, state - leafId)

      if (updatedParent.children.nonEmpty)
        updatedTree
      else
        removeBranch(parent.id, updatedTree)
    }
  }

  // Unused methods:

  @deprecated("we need a mempool of unused transactions")
  override def append(block: ElmBlock): Try[(ElmBlocktree, Option[RollbackTo[ElmBlock]])] = ???

  @deprecated("unused: cannot calculate score without minstate")
  override def score(block: ElmBlock): BigInt = 0

  @deprecated("unnecessary")
  override def chainScore(): BigInt = maxLeaf.accumulatedScore

  @deprecated("unnecessary")
  override def isEmpty: Boolean = blocks.isEmpty

  @deprecated("unused method (actually the place of usage is unused)")
  override def companion: NodeViewComponentCompanion = ???

  @deprecated("unnecessary")
  override def height(): Int = maxLeaf.height

  @deprecated("unnecessary")
  override def heightOf(blockId: BlockId): Option[Int] =
    blocks.get(blockId).map(_.height)

  @deprecated("unnecessary")
  override def discardBlock(): Try[ElmBlocktree] = ???

  @deprecated("unnecessary")
  override def blockAt(height: Int): Option[ElmBlock] = None

  @deprecated("unnecessary")
  override def children(blockId: BlockId): Seq[ElmBlock] = Nil
}
