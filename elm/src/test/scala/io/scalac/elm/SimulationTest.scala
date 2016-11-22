package io.scalac.elm

import io.circe.syntax._
import io.circe.generic.auto._
import io.scalac.elm.simulation.SimResults.NodeResults
import io.scalac.elm.simulation.Simulation
import org.scalatest.{FlatSpec, Matchers}

class SimulationTest extends FlatSpec with Matchers {

  val simulationResults = Simulation.run()

  println(simulationResults.payments.map(_.asJson.spaces4))

  println(simulationResults.nodeResults.asJson.spaces4)


  "Nodes in a simulation" should "accumulate expected funds" in {
    simulationResults.nodeResults.foreach { case (name, NodeResults(expected, actual, _, _)) =>
      assert(expected === actual, s"- funds for node: $name")
    }
  }
}
