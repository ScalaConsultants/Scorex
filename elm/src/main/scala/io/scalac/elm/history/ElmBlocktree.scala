package io.scalac.elm.history

import cats.data.Xor
import io.scalac.elm.history.ElmBlocktree._
import io.scalac.elm.state.ElmMinState
import io.scalac.elm.transaction.{ElmBlock, ElmTransaction}
import io.scalac.elm.util.{ByteKey, Error}
import scorex.core.NodeViewComponentCompanion
import scorex.core.consensus.BlockChain
import scorex.core.consensus.History.{BlockId, HistoryComparisonResult, RollbackTo}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.crypto.signatures.Curve25519

import scala.annotation.tailrec
import scala.util.Try

object ElmBlocktree {
  case class Node(block: ElmBlock, children: Set[ByteKey], height: Int, score: Long, accumulatedScore: Long) {
    def id: ByteKey = block.id.key
    def addChild(childId: ByteKey): Node = copy(children = children + childId)
    def removeChild(childId: ByteKey): Node = copy(children = children - childId)
  }

  val zero: ElmBlocktree = {
    val zeroNode = Node(ElmBlock.zero, Set.empty, 0, 0, 0)
    ElmBlocktree(Map(zeroNode.id -> zeroNode), Set(zeroNode.id))
  }

  case object BlockValidationError extends Error
}

case class ElmBlocktree private(
  blocks: Map[ByteKey, Node],
  leaves: Set[ByteKey]
) extends BlockChain[PublicKey25519Proposition, ElmTransaction, ElmBlock, ElmSyncInfo, ElmBlocktree] {

  override type NVCT = ElmBlocktree

  //TODO: configure
  val N = 8
  val confirmationDepth = 5

  def append(block: ElmBlock, minState: ElmMinState): Error Xor ElmBlocktree = {
    val blockId = block.id.key
    val parentId = block.parentId.key

    if (isValid(block)) {
      val parent = blocks(parentId)
      val score = calculateScore(block, parent.height, minState)
      val updatedParent = parent.addChild(blockId)
      val newNode = Node(block, Set.empty, parent.height + 1, score, parent.accumulatedScore + score)

      val updatedBlocks = blocks + (parentId -> updatedParent) + (blockId -> newNode)
      val updatedTree = ElmBlocktree(updatedBlocks, leaves + blockId)
      val limitedTree = limitBranches(N, updatedTree)
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
  def chainOf(blockId: ByteKey): Stream[Node] =
    blocks.get(blockId).filter(_ != ElmBlock.zero.id.key)
      .map(node => node #:: chainOf(node.id)).getOrElse(Stream.Empty)

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
  def minLeaf: Node =
    leaves.map(blocks).minBy(_.accumulatedScore)

  /**
    * Find a leaf with the highest score - the last block of the main chain
    */
  def maxLeaf: Node =
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
      val updatedTree = ElmBlocktree(blocks - leafId, tree.leaves - leafId)

      if (updatedParent.children.nonEmpty)
        updatedTree
      else
        removeBranch(parent.id, updatedTree)
    }
  }

  private def isValid(block: ElmBlock, minState: ElmMinState): Boolean = {
    // check block signature
    val pubKey = block.generator.pubKeyBytes
    val sygnature = block.generationSignature
    val message = block.bytes
    lazy val isSigned = Curve25519.verify(sygnature, message, pubKey)

    // check if parent exists
    lazy val parent = blocks.get(block.parentId.key).nonEmpty

    //transaction signatures


    //coinstake and one additional transacton
    lazy val correctTransactionCount = block.transactions.exists(_.size >= 2)


    lazy val signed = block.transactions.map(_.forall(t => minState.isValid(t))).getOrElse()

    // check transaction correctness
    // check coins take transaction correctness



    //Curve25519.verify()
    //out.map(o => Curve25519.verify(in.boxKey.signature, o.bytes, o.proposition.pubKeyBytes)).exists(identity)


    leaves(block.id.key)
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
