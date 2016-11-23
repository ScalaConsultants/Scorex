package io.scalac.elm.simulation

import io.scalac.elm.network.NetworkNode
import io.scalac.elm.simulation.SimResults.NodeResults
import io.scalac.elm.simulation.Simulation.Payment
import io.scalac.elm.transaction.ElmBlock
import io.scalac.elm.util.ByteKey

object ResultCruncher {

  def apply(nodes: Seq[NetworkNode], payments: Seq[Payment]): SimResults = {
    val master = nodes.head
    val mainchain = master.mainchain()

    val nodeResults = nodes.map { node =>
      val forgedBlocks = findForgedBlocks(node, mainchain)
      val earnedFees = calcEarnedFees(forgedBlocks)
      val expectedFunds = earnedFees + calcExpectedFundsWithoutEarnedFees(node, payments)
      val actualFunds = node.walletFunds()

      node.name -> NodeResults(expectedFunds, actualFunds, earnedFees, forgedBlocks.length)
    }.toMap

    SimResults(nodeResults, payments)
  }

  private def calcExpectedFundsWithoutEarnedFees(node: NetworkNode, payments: Seq[Payment]): Long = {
    val startingFunds = node.elmConfig.genesis.initialFunds
    payments.foldLeft(startingFunds) {
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
}
