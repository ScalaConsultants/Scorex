package io.scalac.elm.functional

import io.scalac.elm.transaction.ElmBlock
import io.scalac.elm.util.ByteKey

import scala.annotation.tailrec

trait ApiTransactionTestFixture { self: ApiTestFixture =>

  case class ProcessedBlock(id: String, parent: Option[ProcessedBlock])

  case class BlockChainInfo(
    blocks: Set[ElmBlock],
    processedBlocks: Map[String, ProcessedBlock],
    depths: Map[ProcessedBlock, Int]
  ) {

    override def toString: String =
      s"""Block chain info:
         |  Blocks:
         |${blocks.map(block =>
           s"""    Block:
              |    ID:     ${block.id.base58}
              |    Parent: ${block.parentId.base58}
              |    Depth:  ${processedBlocks.get(block.id.base58).flatMap(depths.get).getOrElse("unknown")}
              |    txs:
              |            ${block.txs.mkString("\n            ")}
            """.stripMargin).mkString("\n")}
       """.stripMargin
  }

  private val zeroParentId = "11111111111111111111111111111111"

  private def toProcessedBlock(processed: Map[String, ProcessedBlock] = Map.empty)(block: ElmBlock) =
    block.id.base58 ->ProcessedBlock(
      id = block.id.base58,
      parent = processed.get(block.parentId.base58)
    )

  private def extractZeros(unprocessed: Set[ElmBlock]): (Map[String, ProcessedBlock], Set[ElmBlock]) = {
    val (zeros, children) =  unprocessed.partition(block => block.parentId.base58 == zeroParentId)
    zeros.map(toProcessedBlock()).toMap -> children
  }

  @tailrec
  private def buildTransactionTree(processed: Map[String, ProcessedBlock], unprocessed: Set[ElmBlock]):
      Map[String, ProcessedBlock] =
    if (unprocessed.isEmpty) processed
    else {
      val potentialParents = processed.keySet
      val (children, descendants) = unprocessed.partition(block => potentialParents.contains(block.parentId.base58))
      if (children.isEmpty) {
        print(s"dupa: ${unprocessed.map(_.id.base58).mkString(", ")}")
        processed
      }
      else buildTransactionTree(processed ++ children.map(toProcessedBlock(processed)), descendants)
    }

  private def findLeafs(processed: Map[String, ProcessedBlock]) = {
    val parentIds = processed.values.flatMap(_.parent).map(_.id).toSet
    val allIds = processed.values.map(_.id).toSet
    val leafIds = allIds -- parentIds
    processed.filterKeys(leafIds.contains)
  }

  @tailrec
  private def findBlocksDepth(
    currentBlocks: Set[ProcessedBlock],
    currentDepth: Int = 0,
    knownDepth: Map[ProcessedBlock, Int] = Map.empty
  ): Map[ProcessedBlock, Int] = {
    def isUnknown(block: ProcessedBlock) = !knownDepth.contains(block)
    val newDepth = knownDepth ++ currentBlocks.filter(isUnknown).map { _ -> currentDepth }
    val nextBlocks = currentBlocks.flatMap(_.parent).filter(isUnknown)
    if (nextBlocks.isEmpty) knownDepth
    else findBlocksDepth(nextBlocks, currentDepth + 1, newDepth)
  }

  protected def processBlockchain(node: TestElmApp): BlockChainInfo = {
    val blockchainIds = getBlocks(node)
    val blocks = blockchainIds.map(block => getTransaction(node, block)).toSet
    val (x, y) = extractZeros(blocks) // buildTransactionTree.tupled(extractZeros(blocks)) gave compile error :(
    val processedBlocks = buildTransactionTree(x, y)
    val leafs = findLeafs(processedBlocks)
    val blocksDepth = findBlocksDepth(leafs.values.toSet)
    BlockChainInfo(blocks, processedBlocks, blocksDepth)
  }
}
