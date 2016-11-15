package io.scalac.elm.functional

import com.typesafe.config.ConfigFactory
import io.scalac.elm.ElmApp
import io.scalac.elm.config.{AppInfo, AppConfig}
import org.scalatest._
import org.slf4j.Logger
import scorex.core.network.NetworkController

import scala.annotation.tailrec
import scala.util.{Random, Failure, Success, Try}
import scalaj.http.{HttpOptions, Http}

class FuzzyTransactionTest extends FlatSpec with Matchers with BeforeAndAfterAll {

  case class TestElmApp(appInfo: AppInfo, appConfig: AppConfig) extends ElmApp(appInfo, appConfig) {

    override def log: Logger = super.log

    // to get rid of System.exit(0)
    override def stopAll(): Unit = synchronized {
      log.info("Stopping network services")
      if (settings.upnpEnabled) upnp.deletePort(settings.port)
      networkController ! NetworkController.ShutdownNetwork

      log.info("Stopping actors (incl. block generator)")
      actorSystem.terminate()
    }
  }

  case class NodeExpectedState(funds: Int)

  implicit class NodeExpectedStateUpdater(states: Map[TestElmApp, NodeExpectedState]) {

    def fundsChangedBy(node: TestElmApp, change: Int): Map[TestElmApp, NodeExpectedState] = {
      val state = states(node)
      states.updated(node, state.copy(state.funds + change))
    }
  }

  val configs = (1 to 4) map (i => AppConfig.load(ConfigFactory.load(s"application-fuzzy-test-$i.conf")))
  val nodes = (1 to 4) map (i => AppInfo(s"elm-test-$i")) zip configs map TestElmApp.tupled
  val node2port = nodes zip (configs map (_.settings.rpcPort)) toMap

  private def getWalletAddress(node: TestElmApp) = Try {
    val uri = s"http://localhost:${node2port(node)}/wallet/address"
    node.log.debug(s"Trying to query wallet address: $uri")
    Http(uri).header("Accept", "text/plain").option(HttpOptions.readTimeout(1000)).asString.body
  }

  private def getWalletFunds(node: TestElmApp) = Try {
    val uri = s"http://localhost:${node2port(node)}/wallet/funds"
    node.log.debug(s"Trying to query wallet address: $uri")
    Http(uri).header("Accept", "text/plain").option(HttpOptions.readTimeout(1000)).asString.body.toInt
  } getOrElse (throw new IllegalStateException("Funds should be always accessible"))

  private def makePayment(sender: TestElmApp, address: String, amount: Int, priority: Int) = Try {
    val uri = s"http://localhost:${node2port(sender)}/wallet/payment"
    sender.log.debug(s"Trying to query wallet address: $uri")
    Http(uri).header("Accept", "text/plain").option(HttpOptions.readTimeout(1000))
        .param("address", address)
        .param("amount", amount.toString)
        .param("priority", priority.toString)
        .asString.body
  }

  override protected def beforeAll(): Unit = nodes foreach { node =>
    @tailrec
    def waitForInitialization: Unit = {
      node.synchronized(node.wait(100))
      getWalletAddress(node) match {
        case Success(address) if address.nonEmpty || address == "0" =>
        case Success(address) =>
          node.log.debug(s"Returned empty message")
          waitForInitialization
        case Failure(error)   =>
          node.log.debug(s"Returned error message $error")
          waitForInitialization
      }
    }

    node.run()
    waitForInitialization
    node.log.info(s"Node initialized for testing")
  }

  "Series of random transactions requests" should "result in successfully finalized transactions" in {

    val addresses = nodes map getWalletAddress map (_.getOrElse(
      throw new IllegalStateException("All addresses should be known at this point")))
    val node2Address = nodes zip addresses toMap

    nodes foreach { node =>
      node.log.trace(s"My address is ${node2Address(node)}")
    }

    @tailrec
    def performRandomTransactions(node2State: Map[TestElmApp, NodeExpectedState], transactionsLeft: Int):
        Map[TestElmApp, NodeExpectedState] = {

      if (transactionsLeft <= 0) node2State
      else {
        val maxFunds = node2State.values map (_.funds) max
        val priority = Random.nextInt(maxFunds / 10 / 10)
        val fee = priority * 10
        val maxAmount = ((maxFunds - fee) * 0.3).toInt
        val amount = Random.nextInt(maxAmount)

        val sender = Random.shuffle(node2State filter { case (_, s) => s.funds >= amount + fee }).head._1
        val receiver = Random.shuffle(nodes).head
        val receiverAddress = node2Address(receiver)

        makePayment(sender, receiverAddress, amount ,fee)
        sender.log.debug(s"Sent $amount with $fee of fee to $receiverAddress")

        // TODO: figure out who created block and received fee

        val newStates = node2State
          .fundsChangedBy(sender, -(amount + fee))
          .fundsChangedBy(receiver, amount)

        synchronized(wait(100))

        performRandomTransactions(newStates, transactionsLeft -1)
      }
    }

    val initialNode2State = nodes zip (nodes map getWalletFunds map NodeExpectedState) toMap
    val finalNode2State =  performRandomTransactions(initialNode2State, 30)
    val finalNode2Funds = nodes zip (nodes map getWalletFunds) toMap

    nodes foreach { node =>
      val expectedFunds = finalNode2State(node).funds
      val actualFunds = finalNode2Funds(node)
      node.log.info(s"${node.applicationName} has $actualFunds - extected $expectedFunds")
      actualFunds shouldEqual expectedFunds
    }
  }

  override protected def afterAll(): Unit = nodes foreach { n => try { n.stopAll() } catch { case _: Throwable => } }
}
