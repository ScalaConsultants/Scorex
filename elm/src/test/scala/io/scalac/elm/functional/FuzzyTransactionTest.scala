package io.scalac.elm.functional

import org.scalatest._

import scala.annotation.tailrec
import scala.util.Random

class FuzzyTransactionTest extends ApiTestFixture with Matchers {

  val maxFeePercentage = 0.1
  val maxAmountPercentage = 0.3
  val numberOfTransactions = 30
  val delayBetweenTransactions = 100

  "Series of random transactions requests" should "result in successfully finalized transactions" in {
    // given
    nodes.foreach { node => node.log.trace(s"${node.applicationName} address is ${node2Address(node)}") }
    val initialNode2State = nodes.zip(nodes.map(getWalletFunds).map(NodeExpectedState)).toMap

    // when
    val finalNode2State = performRandomTransactions(initialNode2State, numberOfTransactions)
    val finalNode2Funds = nodes.zip(nodes.map(getWalletFunds)).toMap

    // then
    nodes.map { node =>
      val expectedFunds = finalNode2State(node).funds
      val actualFunds = finalNode2Funds(node)
      node.log.info(s"${node.applicationName} has $actualFunds - expected $expectedFunds")
      (expectedFunds, actualFunds)
    }.foreach { case (expectedFunds, actualFunds) =>  actualFunds shouldEqual expectedFunds }
  }

  @tailrec
  private def performRandomTransactions(node2State: Map[TestElmApp, NodeExpectedState], transactionsLeft: Int):
      Map[TestElmApp, NodeExpectedState] =
    if (transactionsLeft <= 0) node2State
    else {
      val maxFunds = node2State.values.map(_.funds).max
      val fee = Random.nextInt((maxFunds / maxFeePercentage).toInt)
      val maxAmount = ((maxFunds - fee) * maxAmountPercentage).toInt
      val amount = Random.nextInt(maxAmount)

      val sender = Random.shuffle(node2State collect { case (node, state) if state.funds >= amount + fee => node }).head
      val receiver = Random.shuffle(nodes).head

      makePayment(sender, receiver, amount ,fee)
      sender.log.debug(s"Sent $amount with $fee of fee to ${receiver.applicationName}")
      receiver.log.debug(s"Sent $amount with $fee of fee from ${sender.applicationName}")

      // TODO: figure out who created block and received fee

      val newStates = node2State
        .fundsChangedBy(sender, -(amount + fee))
        .fundsChangedBy(receiver, amount)

      synchronized(wait(delayBetweenTransactions))

      performRandomTransactions(newStates, transactionsLeft -1)
    }
}
