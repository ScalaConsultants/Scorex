package io.scalac.elm

import io.scalac.elm.simulation.SimResults.NodeResults
import io.scalac.elm.simulation.Simulation
import io.scalac.elm.simulation.Simulation.Payment
import org.scalatest.{FlatSpec, Matchers}

class SimulationTest extends FlatSpec with Matchers {

  val simulationResults = Simulation.run()

  import io.circe.syntax._
  import io.circe.generic.auto._

  println(simulationResults.payments.map {
    case Payment(id, sender, recipient, amount, fee) =>
      Map[String, String]("id" -> id, "sender" -> sender.publicKey, "recipient" -> recipient.publicKey, "amount" -> amount.toString, "fee" -> fee.toString)
  }.asJson.spaces4)

  println(simulationResults.nodeResults.asJson.spaces4)


  "Nodes in a simulation" should "accumulate expected funds" in {
    simulationResults.nodeResults.foreach { case (name, NodeResults(expected, actual, _, _)) =>
      assert(expected === actual, s"- funds for node: $name")
    }
  }
}
