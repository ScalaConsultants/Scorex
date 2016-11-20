package io.scalac.elm.simulation

import io.scalac.elm.simulation.SimResults.NodeResults
import io.scalac.elm.simulation.Simulation.Payment

object SimResults {
  case class NodeResults(expectedFunds: Long, actualFunds: Long, earnedFees: Long, forgedMainchainBlocks: Int)
}

case class SimResults(nodeResults: Map[String, NodeResults], payments: Seq[Payment])
