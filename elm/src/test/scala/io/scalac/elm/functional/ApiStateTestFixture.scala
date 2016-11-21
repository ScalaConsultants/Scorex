package io.scalac.elm.functional

trait ApiStateTestFixture { self: ApiTestFixture =>

  case class NodeExpectedState(
      initialFunds: Long,
      estimatedFunds: Long = 0,
      sent: Map[String, Long] = Map.empty,
      received: Map[String, Long] = Map.empty,
      fees: Map[String, Long] = Map.empty
  ) {

    override def toString: String =
      s"""Node expected state:
         | * initial funds:     $initialFunds
         | * estimated funds    $estimatedFunds
         | * send transactions:
         |    ${sent.mkString("\n    ")}
         | * received transactions:
         |    ${received.mkString("\n    ")}
         | * fees:
         |    ${fees.mkString("\n    ")}
       """.stripMargin
  }

  implicit class NodeExpectedStateUpdater(states: Map[TestElmApp, NodeExpectedState]) {

    def fundsSentBy(node: TestElmApp, amount: Int, fee: Int, transactionId: String): Map[TestElmApp, NodeExpectedState] = {
      val state = states(node)
      states.updated(node, state.copy(state.estimatedFunds - amount - fee, sent = state.sent + (transactionId -> -amount)))
    }

    def fundsReceivedBy(node: TestElmApp, amount: Int, transactionId: String): Map[TestElmApp, NodeExpectedState] = {
      val state = states(node)
      states.updated(node, state.copy(state.estimatedFunds + amount, received = state.received + (transactionId -> amount)))
    }

    def feeReceivedBy(node: TestElmApp, fee: Int, transactionId: String): Map[TestElmApp, NodeExpectedState] = {
      val state = states(node)
      states.updated(node, state.copy(state.estimatedFunds + fee, fees = state.fees + (transactionId -> fee)))
    }
  }
}
