package io.scalac.elm.functional

import com.typesafe.config.ConfigFactory
import io.scalac.elm.ElmApp
import io.scalac.elm.config.{AppInfo, AppConfig}
import org.scalatest._
import org.slf4j.Logger
import scorex.core.network.NetworkController

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
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

  val configs = (1 to 4) map (i => AppConfig.load(ConfigFactory.load(s"application-fuzzy-test-$i.conf")))
  val nodes = (1 to 4) map (i => AppInfo(s"elm-test-$i")) zip configs map TestElmApp.tupled
  val node2port = nodes zip (configs map (_.settings.rpcPort)) toMap

  private def getWalletAddress(node: TestElmApp) = Try {
    val uri = s"http://localhost:${node2port(node)}/wallet/address"
    node.log.debug(s"Trying to query wallet address: $uri")
    Http(uri).header("Accept", "text/plain").option(HttpOptions.readTimeout(1000)).asString.body
  }

  override protected def beforeAll(): Unit = nodes foreach { node =>
    @tailrec
    def waitForInitialization: Unit = {
      node.synchronized(node.wait(100))
      getWalletAddress(node) match {
        case Success(address) if address.nonEmpty =>
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

    val addresses = nodes map getWalletAddress map (_.get)
    val node2Address = nodes zip addresses toMap

    nodes foreach { node =>
      node.log.trace(s"My address is ${node2Address(node)}")
    }

    // TODO:
    // query all node for funds
    // filter those that have some funds
    // select at random and transfer no more than 30% of money
    // repeat x times
    // check if final amount of money everywhere matches expected
  }

  override protected def afterAll(): Unit = nodes foreach { n => try { n.stopAll() } catch { case _: Throwable => } }
}
