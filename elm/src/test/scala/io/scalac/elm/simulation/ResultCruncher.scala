package io.scalac.elm.simulation

import io.circe.syntax._
import io.circe.generic.auto._
import io.scalac.elm.config.ElmConfig.GenesisConf
import io.scalac.elm.network.NetworkNode
import io.scalac.elm.simulation.SimResults.NodeResults
import io.scalac.elm.simulation.Simulation.Payment
import io.scalac.elm.transaction.ElmBlock
import io.scalac.elm.util.ByteKey
import org.slf4j.LoggerFactory

object ResultCruncher {

  private val log = LoggerFactory.getLogger(getClass)

  def apply(nodes: Seq[NetworkNode], allPayments: Seq[Payment], failedTxIds: Set[String],
    mainchainTxIds: Set[String]): SimResults = {

    val successfulPayments = allPayments.filter(p => mainchainTxIds(p.id))

    val genesisCreator = nodes.find(_.elmConfig.genesis.generate).get
    val mainchain = genesisCreator.mainchain()
    val initialFunds = calcInitialFunds(genesisCreator, nodes)

    log.debug("Nodes initial funds: " + initialFunds.asJson.spaces4)

    val nodeResults = nodes.map { node =>
      val forgedBlocks = findForgedBlocks(node, mainchain)
      val earnedFees = calcEarnedFees(forgedBlocks)
      val expectedFunds = earnedFees + calcExpectedFundsWithoutEarnedFees(node, initialFunds(node.publicKey), successfulPayments)
      val actualFunds = node.walletFunds()

      node.name -> NodeResults(expectedFunds, actualFunds, earnedFees, forgedBlocks.length)
    }.toMap

    SimResults(nodeResults, allPayments, failedTxIds, mainchainTxIds)
  }

  private def calcExpectedFundsWithoutEarnedFees(node: NetworkNode, initialFunds: Long, payments: Seq[Payment]): Long = {
    payments.foldLeft(initialFunds) {
      case (funds, Payment(_, sender, recipient, amount, fee)) =>
        if (sender == node.publicKey)
          funds - amount - fee
        else if (recipient == node.publicKey)
          funds + amount
        else
          funds
    }
  }

  private def findForgedBlocks(node: NetworkNode, mainchain: List[ElmBlock]): List[ElmBlock] =
    mainchain.filter(_.generator.pubKeyBytes.base58 == node.publicKey)

  private def calcEarnedFees(forgedBlocks: List[ElmBlock]): Long =
    forgedBlocks.flatMap(_.txs.tail).map(_.fee).sum

  private def calcInitialFunds(genesisCreator: NetworkNode, nodes: Seq[NetworkNode]): Map[String, Long] = {
    val GenesisConf(_, total, grains, addresses) = genesisCreator.elmConfig.genesis
    val outputs = (0 to (grains / addresses.size)).flatMap(_ => addresses).take(grains).map(_ -> total / grains)
    outputs.groupBy(_._1).mapValues(_.map(_._2).sum)
  }
}
