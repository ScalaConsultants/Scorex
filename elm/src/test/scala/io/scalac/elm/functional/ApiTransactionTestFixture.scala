package io.scalac.elm.functional

import io.scalac.elm.transaction.ElmBlock
import scorex.core.block.Block

import scala.annotation.tailrec

trait ApiTransactionTestFixture { self: ApiTestFixture =>

  type BlockId = Block.BlockId

  case class ProcessedBlock(
      id: BlockId,
      parent: Option[ProcessedBlock],
      generator: TestElmApp
  )

  private val zeroParentId = "11111111111111111111111111111111"

  private def toProcessedBlock(processed: Map[BlockId, ProcessedBlock] = Map.empty)(block: ElmBlock) =
    block.id ->ProcessedBlock(
      id = block.id,
      parent = processed.get(block.parentId),
      generator = node2Address.collect { case (node, address) if address == block.generator.address => node }.head
    )

  private def extractZeros(unprocessed: Set[ElmBlock]): (Map[BlockId, ProcessedBlock], Set[ElmBlock]) = {
    val (zeros, children) =  unprocessed.partition(_.parentId.toString == zeroParentId)
    zeros.map(toProcessedBlock()).toMap -> children
  }

  @tailrec
  private def buildTransactionTree(processed: Map[BlockId, ProcessedBlock], unprocessed: Set[ElmBlock]):
      Map[BlockId, ProcessedBlock] =
    if (unprocessed.isEmpty) processed
    else {
      val potentialParents = processed.keySet
      val (children, descendants) = unprocessed.partition(block => potentialParents.contains(block.parentId))
      buildTransactionTree(processed ++ children.map(toProcessedBlock(processed)), descendants)
    }

  private def findLeafs(processed: Map[BlockId, ProcessedBlock]) = {
    val parentIds = processed.values.flatMap(_.parent).map(_.id).toSet
    val allIds = processed.values.map(_.id).toSet
    val leafKeys = allIds -- parentIds
    processed.filterKeys(leafKeys.contains)
  }

  // TODO: block -> depth
  // TODO: filter by confirmationDepth

  protected def processTransactions(node: TestElmApp): Unit = {
    val blockchainIds = getBlocks(node)
    val blocks = blockchainIds.map(block => getTransaction(node, block)).toSet
    val (x, y) = extractZeros(blocks)
    val blockMap = buildTransactionTree(x, y) // buildTransactionTree.tupled(extractZeros(blocks)) gave compile error :(
    val leafs = findLeafs(blockMap)
    leafs
  }
}
