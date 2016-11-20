package io.scalac.elm.functional

import com.typesafe.config.ConfigFactory
import io.scalac.elm.ElmApp
import io.scalac.elm.config.{AppConfig, AppInfo}
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import org.slf4j.Logger
import scorex.core.network.NetworkController

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import scalaj.http.{Http, HttpOptions}

trait ApiTestFixture extends FlatSpec with BeforeAndAfterAll {

  // test running utils

  case class TestElmApp(appInfo: AppInfo, appConfig: AppConfig) extends ElmApp(appInfo, appConfig) {

    // expose logger for test debugging
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

  val queryTimeout = 550
  val maxAttempts = 10
  val configs = (1 to 4).map(i => AppConfig.load(ConfigFactory.load(s"application-fuzzy-test-$i.conf")))
  val nodes = (1 to 4).map(i => AppInfo(s"elm-test-$i")).zip(configs).map(TestElmApp.tupled)
  val node2port = nodes.zip(configs.map(_.settings.rpcPort)).toMap

  lazy val node2Address = nodes.zip(nodes.map(getWalletAddress)).toMap

  // API query utils

  implicit class QueryApi(node: TestElmApp) {

    def queryApi[Result](relUri: String, params: Map[String, String] = Map.empty, desc: String, errMsg: String)
                        (parseResult: String => Result): Result = Try {
      val uri = s"http://localhost:${node2port(node)}/$relUri"
      node.log.debug(s"Trying to $desc: $uri")
      val result = Http(uri)
        .header("Accept", "text/plain")
        .option(HttpOptions.readTimeout(queryTimeout))
        .params(params)
        .asString.body
      node.log.debug(s"Received $result from $uri")
      parseResult(result)
    } match {
      case Success(value) => value
      case Failure(error) => throw new IllegalStateException(errMsg, error)
    }
  }

  protected def getWalletAddress(node: TestElmApp) = node
    .queryApi("wallet/address", desc = "query wallet address", errMsg = "All addresses should be known") {
      ApiResponseDeserialization.toAddress
    }

  protected def getWalletFunds(node: TestElmApp) = node
    .queryApi("wallet/funds", desc = "query wallet funds", errMsg = "Funds should be always accessible") {
      ApiResponseDeserialization.toFunds
    }

  protected def makePayment(sender: TestElmApp, receiver: TestElmApp, amount: Int, fee: Int) = sender
    .queryApi("wallet/payment", desc = "make a payment", errMsg = "Payment should always be possible", params = Map(
      "address" -> node2Address(receiver),
      "amount"-> amount.toString,
      "fee" -> fee.toString
    )) {
      ApiResponseDeserialization.toPayment
    }

  protected def getBlocks(node: TestElmApp) = node
    .queryApi("blockchain/block", desc = "query blockchain", errMsg = "Blockchain should always be accessible") {
      ApiResponseDeserialization.toBlockAddresses
    }

  protected def getTransaction(node: TestElmApp, blockId: String) = node
    .queryApi(s"blockchain/block/$blockId", desc = s"query block", errMsg = "Block info should always be accessible") {
      ApiResponseDeserialization.toBlock
    }

  // test bootstrap and teardown

  override protected def beforeAll(): Unit = nodes.map { node =>
    node.run()
    node
  }.foreach { node =>
    @tailrec
    def waitForInitialization(attemptsLeft: Int): Unit =
      if (attemptsLeft <= 0) throw new IllegalArgumentException("Exceeded attempts number")
      else {
        node.synchronized(node.wait(queryTimeout))
        Try(getWalletAddress(node) -> getWalletFunds(node)) match {
          case Success((address, _)) if address.nonEmpty || address == "0" =>
          case Success((address, _)) =>
            node.log.debug(s"Returned empty message")
            waitForInitialization(attemptsLeft - 1)
          case Failure(error) =>
            node.log.debug(s"Returned error message: $error")
            waitForInitialization(attemptsLeft - 1)
        }
      }

    waitForInitialization(maxAttempts)
    node.log.info(s"Node initialized for testing")
  }

  override protected def afterAll(): Unit = nodes.foreach { n => try { n.stopAll() } catch { case _: Throwable => } }
}
