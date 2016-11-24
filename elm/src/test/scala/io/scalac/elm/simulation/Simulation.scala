package io.scalac.elm.simulation

import io.circe.generic.auto._
import io.circe.syntax._
import io.scalac.elm.config.SimConfig
import io.scalac.elm.core.ElmNodeViewHolder.PaymentResponse
import io.scalac.elm.network.{NetworkNode, NodeManager}
import io.scalac.elm.simulation.Simulation.Payment
import io.scalac.elm.util.ARM._
import io.scalac.elm.util.ByteKey
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.util.Random


object Simulation {
  case class Payment(id: String, sender: String, recipient: String, amount: Long, fee: Long)

  def run(conf: SimConfig = SimConfig.load()): SimResults =
    new Simulation(conf).run()
}

class Simulation private(simConfig: SimConfig) {

  val genesisSyncAttempts = 20
  val gesesisSyncInterval = 2000L

  private val log = LoggerFactory.getLogger(getClass)

  log.info(s"Random seed: ${simConfig.randomSeed}")
  private val random = new Random(simConfig.randomSeed)

  private val nodeManager = new NodeManager(simConfig)

  private def run(): SimResults = {
    using(nodeManager.initializeNodes()) { nodes =>

      awaitGenesisSynchronization(nodes, genesisSyncAttempts)

      log.info(s"Running simulation. ${simConfig.transactions.count} transactions to be made")

      val payments = runTransactions(nodes)
      val failed = awaitSynchronization(nodes, payments, simConfig.sync.attempts)
      log.info(s"${failed.size} transactions failed")

      val results = ResultCruncher(nodes, payments, failed, getMainchainTransactions(nodes, payments))

      log.info("Simulation finished")
      results

    }() { nodes =>
      if (simConfig.shutdownNodes)
        nodeManager.shutdownNodes(nodes)
    }
  }

  private def runTransactions(nodes: Seq[NetworkNode]): List[Payment] = {
    val progressFreq = 10
    val payments = mutable.ListBuffer.empty[Payment]

    while (payments.size < simConfig.transactions.count) {

      val payment = makePayment(nodes)
      payment.foreach { p =>
        log.debug(s"New payment made: ${payment.asJson.noSpaces}")
        payments += p
      }

      if (payment.isDefined && (payments.size % progressFreq == 0))
        log.info(s"Made ${payments.size} out of ${simConfig.transactions.count} transactions")

      Thread.sleep(simConfig.transactions.interval.toMillis)
    }

    log.info(s"Made all ${payments.size} transactions")
    payments.toList
  }

  private def makePayment(nodes: Seq[NetworkNode]): Option[Payment] = {
    val sortedNodes =
      random.shuffle(nodes)
        .map { n =>
          val funds = n.walletFunds()
          log.debug(s"Node $n has $funds funds")
          n -> funds
        }
        .sortBy(_._2).reverse.take(2).map(_._1)

    val sender = sortedNodes.head
    val recipient = sortedNodes.last

    val ratio = randomRatio()
    val fee = randomFee()
    val PaymentResponse(maybeId, amount) = sender.makePayment(recipient.publicKey, ratio, fee, simConfig.transactions.minFunds)
    maybeId.map(Payment(_, sender.publicKey, recipient.publicKey, amount, fee))
  }

  private def randomRatio(): Double = {
    val min = simConfig.transactions.minAmount
    val max = simConfig.transactions.maxAmount
    min + random.nextDouble() * (max - min)
  }

  private def randomFee(): Long = {
    val min = simConfig.transactions.minFee
    val max = simConfig.transactions.maxFee
    min + math.abs(random.nextLong()) % (max - min + 1)
  }

  private def awaitGenesisSynchronization(nodes: Seq[NetworkNode], attemptsLeft: Int): Unit = {
    log.info(s"Awaiting genesis block synchronization, attempts left: $attemptsLeft")
    Thread.sleep(gesesisSyncInterval)
    val sameLeaves = nodes.map(_.leaves().toSet).toSet.size == 1
    if (!sameLeaves) {
      if (attemptsLeft == 0) {
        log.error("Nodes failed to synchronize genesis block")
      } else {
        awaitGenesisSynchronization(nodes, attemptsLeft - 1)
      }
    }
  }

  private def awaitSynchronization(nodes: Seq[NetworkNode], payments: Seq[Payment], attemptsLeft: Int): Set[String] = {
    log.info(s"Awaiting blocktree synchronization, attempts left: $attemptsLeft")
    Thread.sleep(simConfig.sync.interval.toMillis)

    val txsNotIncluded = payments.map(_.id).toSet diff nodes.head.mainchain().flatMap(_.txs).map(_.id.base58).toSet
    val failed = nodes.flatMap(_.failed()).toSet
    val onlyFailedNotIncluded = failed == txsNotIncluded
    val sameLeaves = nodes.map(_.leaves().toSet).toSet.size == 1

    val inSync = onlyFailedNotIncluded && sameLeaves

    if (!inSync) {
      if (attemptsLeft == 0) {
        val sameMainchains = nodes.map(_.mainchainIds()).toSet.size == 1
        log.info(s"Number of transactions not included: ${txsNotIncluded.size}. Mainchains are the same: $sameMainchains. " +
          s"Leaves are the same: $sameLeaves")
        log.error("Nodes failed to synchronize blocktrees!")
        failed
      }
      else
        awaitSynchronization(nodes, payments, attemptsLeft - 1)
    } else failed
  }

  private def getFailedTransactions(nodes: Seq[NetworkNode]): Set[String] =
    nodes.flatMap(_.failed()).toSet

  private def getMainchainTransactions(nodes: Seq[NetworkNode], payments: Seq[Payment]): Set[String] = {
    val mainchains = nodes.map(_.mainchain())
    val nodesMainTxs = mainchains.map(_.flatMap(_.txs).map(_.id.base58).toSet)
    nodesMainTxs.foldLeft(payments.map(_.id).toSet)(_ intersect _)
  }


}
