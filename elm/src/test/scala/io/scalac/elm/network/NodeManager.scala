package io.scalac.elm.network

import com.typesafe.config.{Config, ConfigFactory}
import io.scalac.elm.ElmApp
import io.scalac.elm.config.{ElmConfig, SimConfig}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

class NodeManager(simConfig: SimConfig) {
  class MockNode(override val elmConfig: ElmConfig) extends NetworkNode(null, simConfig) {
    override def run(): Unit = ()
    override def stopAll(): Unit = ()
  }

  private val log = LoggerFactory.getLogger(getClass)

  private val initInterval = 1000
  private val initAttempts = 20

  lazy val mockNode = new MockNode(
    ElmConfig.load(
      ConfigFactory.parseResources("simulation-node1.conf")
        .withFallback(ConfigFactory.load("simulation-common.conf"))))

  def initializeNodes(): Seq[NetworkNode] = {
    val commonConf = ConfigFactory.load("simulation-common.conf")

    for (i <- 1 to simConfig.networkSize)
      yield initializeNode(i, commonConf)
  }

  def shutdownNodes(nodes: Seq[NetworkNode]): Unit =
    nodes.foreach(_.stopAll())


  private def initializeNode(i: Int, commonConf: Config): NetworkNode = {
    val mergedConf = ConfigFactory.parseResources(s"simulation-node$i.conf").withFallback(commonConf)
    val app = ElmApp(ElmConfig.load(mergedConf))
    val node = new NetworkNode(app, simConfig)
    node.run()

    waitForInitialization(node, initAttempts)
    node
  }

  private def waitForInitialization(node: NetworkNode, attemptsLeft: Int): Unit = {
    node.synchronized(node.wait(initInterval))

    Try(node.walletAddress()) match {
      case Success(_) =>
        log.info(s"Node ${node.name} initialized")

      case Failure(error) =>
        log.debug(s"Node ${node.name} error message: $error")
        if (attemptsLeft > 0)
          waitForInitialization(node, attemptsLeft - 1)
        else
          throw error
    }
  }
}
