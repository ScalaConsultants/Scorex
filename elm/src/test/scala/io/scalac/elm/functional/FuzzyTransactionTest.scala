package io.scalac.elm.functional

import io.scalac.elm.transaction.{ElmBlock, ElmTransaction}
import io.scalac.elm.util.ByteKey
import org.scalatest._

import scala.annotation.tailrec
import scala.util.Random

class FuzzyTransactionTest
    extends ApiTestFixture
      with ApiStateTestFixture
      with ApiTransactionTestFixture
      with Matchers {

  val maxFeePercentage = 0.1
  val maxAmountPercentage = 0.3
  val numberOfTransactions = 30
  val delayBetweenTransactions = 100
  val confirmationDepth = 3

  "Series of random transactions requests" should "result in successfully finalized transactions" in {
    // given
    nodes.foreach { node => node.log.trace(s"${node.applicationName} address is ${node2Address(node)}") }
    val initialNode2State = nodes.zip(nodes.map(getWalletFunds).map(NodeExpectedState(_))).toMap

    // when
    val finalNode2State = performRandomTransactions(initialNode2State, numberOfTransactions)
    val finalNode2Funds = nodes.zip(nodes.map(getWalletFunds)).toMap
    val blockchainInfos = nodes.map(node => node -> processBlockchain(node)).toMap
    blockchainInfos.foreach { case (node, chain) =>
      node.log.info(s"Node ${node2Address(node)} chain after transactions => $chain")
    }
    val (confirmedBlocks, confirmedTransactions) = confirmedBlocksAndTransactions(blockchainInfos)
    val nodeStateUpdater = updateNodeState(confirmedBlocks, confirmedTransactions)(_, _)
    val nodesAndExpectations = finalNode2State.map(nodeStateUpdater.tupled)

    // then
    nodesAndExpectations.foreach { case (node, state) =>
      node.log.info(s"Node ${node2Address(node)} state before confirmations => ${finalNode2State(node)}")
      node.log.info(s"Node ${node2Address(node)} state after confirmations => $state")
    }
    nodesAndExpectations.foreach { case (node, state) =>
      val actualFunds = finalNode2Funds(node)
      val expectedFunds = state.initialFunds - state.sent.values.sum + state.received.values.sum + state.fees.values.sum
      actualFunds shouldEqual expectedFunds
    }
  }

  @tailrec
  private def performRandomTransactions(node2State: Map[TestElmApp, NodeExpectedState], transactionsLeft: Int):
      Map[TestElmApp, NodeExpectedState] =
    if (transactionsLeft <= 0) node2State
    else {
      val maxFunds = node2State.values.map(_.estimatedFunds).max
      val fee = Random.nextInt((maxFunds / maxFeePercentage).toInt + 1)
      val maxAmount = ((maxFunds - fee) * maxAmountPercentage).toInt
      val amount = Random.nextInt(maxAmount + 1)

      val sender = Random.shuffle(node2State collect { case (node, state) if state.estimatedFunds >= amount + fee => node }).head
      val receiver = Random.shuffle(nodes).head

      val transactionId = makePayment(sender, receiver, amount ,fee)
      sender.log.debug(s"Sent $amount with $fee of fee to ${receiver.applicationName}")
      receiver.log.debug(s"Sent $amount with $fee of fee from ${sender.applicationName}")

      val newStates = node2State
        .fundsSentBy(sender, amount, fee, transactionId)
        .fundsReceivedBy(receiver, amount, transactionId)

      synchronized(wait(delayBetweenTransactions))

      performRandomTransactions(newStates, transactionsLeft - 1)
    }

  private def confirmedBlocksAndTransactions(blockchainInfos: Map[TestElmApp, BlockChainInfo]) = {
    val bn = nodes.map { node =>
      val BlockChainInfo(blocks, _, depths) = blockchainInfos(node)
      val blocksById = blocks.map(block => block.id.base58 -> block).toMap
      val confirmedNodeBlocks = depths
        .collect { case (block, depth) if depth >= confirmationDepth => blocksById(block.id) }.toSet
      val confirmedNodeTransactions = confirmedNodeBlocks.flatMap(_.txs)
      confirmedNodeBlocks -> confirmedNodeTransactions
    }
    bn.flatMap(_._1).toSet -> bn.flatMap(_._2).toSet
  }

  private def updateNodeState(blocks: Set[ElmBlock], transactions: Set[ElmTransaction])
                             (node: TestElmApp, state: NodeExpectedState) = {
    val nodeAddress = node2Address(node)
    val transactionIds = transactions.map(t => t.id.base58)
    val transactionSignedByNodeIds = blocks.filter { _.generator.address == nodeAddress }.flatMap { _.txs.map(_.id) }
    val confirmedSent = state.sent.filterKeys(transactionIds.contains)
    val confirmedReceived = state.received.filterKeys(transactionIds.contains)
    val feesFromSigning = transactions.filter { t => transactionSignedByNodeIds.contains(t.id) }.map { t =>
      t.id.base58 -> t.fee
    }.toMap
    node -> state.copy(sent = confirmedSent, received = confirmedReceived, fees = feesFromSigning)
  }
}
