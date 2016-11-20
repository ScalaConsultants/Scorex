package io.scalac.elm.functional

trait ApiStateTestFixture { self: ApiTestFixture =>

  case class NodeExpectedState(funds: Int)

  implicit class NodeExpectedStateUpdater(states: Map[TestElmApp, NodeExpectedState]) {

    def fundsChangedBy(node: TestElmApp, change: Int): Map[TestElmApp, NodeExpectedState] = {
      val state = states(node)
      states.updated(node, state.copy(state.funds + change))
    }
  }
}
