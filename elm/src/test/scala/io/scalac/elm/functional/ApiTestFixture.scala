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

  val timeout = 550
  val maxAttempts = 10
  val configs = (1 to 4).map(i => AppConfig.load(ConfigFactory.load(s"application-fuzzy-test-$i.conf")))
  val nodes = (1 to 4).map(i => AppInfo(s"elm-test-$i")).zip(configs).map(TestElmApp.tupled)
  val node2port = nodes.zip(configs.map(_.settings.rpcPort)).toMap

  lazy val node2Address = nodes.zip(nodes.map(getWalletAddress)).toMap

  protected def getWalletAddress(node: TestElmApp) = Try {
    val uri = s"http://localhost:${node2port(node)}/wallet/address"
    node.log.debug(s"Trying to query wallet address: $uri")
    val result = Http(uri).header("Accept", "text/plain").option(HttpOptions.readTimeout(timeout)).asString.body
    node.log.debug(s"Received $result from $uri")
    result
  } match {
    case Success(value) => value
    case Failure(error) => throw new IllegalStateException("All addresses should be known", error)
  }

  protected def getWalletFunds(node: TestElmApp) = Try {
    val uri = s"http://localhost:${node2port(node)}/wallet/funds"
    node.log.debug(s"Trying to query wallet address: $uri")
    val result = Http(uri).header("Accept", "text/plain").option(HttpOptions.readTimeout(timeout)).asString.body
    node.log.debug(s"Received $result from $uri")
    result.toInt
  } match {
    case Success(value) => value
    case Failure(error) => throw new IllegalStateException("Funds should be always accessible", error)
  }

  protected def makePayment(sender: TestElmApp, address: String, amount: Int, fee: Int) = Try {
    val uri = s"http://localhost:${node2port(sender)}/wallet/payment"
    sender.log.debug(s"Trying to query wallet address: $uri")
    val result = Http(uri).header("Accept", "text/plain").option(HttpOptions.readTimeout(timeout))
      .param("address", address)
      .param("amount", amount.toString)
      .param("fee", fee.toString)
      .asString.body
    sender.log.debug(s"Received $result from $uri")
    result
  } match {
    case Success(value) => value
    case Failure(error) => throw new IllegalStateException("Payment call should always be possible", error)
  }

  override protected def beforeAll(): Unit = nodes.map { node =>
    node.run()
    node
  }.foreach { node =>
    @tailrec
    def waitForInitialization(attemptsLeft: Int): Unit =
      if (attemptsLeft <= 0) throw new IllegalStateException("Unable to initialize test properly")
      else {
        node.synchronized(node.wait(timeout))
        Try(getWalletAddress(node) -> getWalletFunds(node)) match {
          case Success((address, _)) if address.nonEmpty || address == "0" =>
          case Success((address, _)) =>
            node.log.debug(s"Returned empty message")
            waitForInitialization(attemptsLeft - 1)
          case Failure(error)   =>
            node.log.debug(s"Returned error message: $error")
            waitForInitialization(attemptsLeft - 1)
        }
      }

    waitForInitialization(maxAttempts)
    node.log.info(s"Node initialized for testing")
  }

  override protected def afterAll(): Unit = nodes.foreach { n => try { n.stopAll() } catch { case _: Throwable => } }
}
