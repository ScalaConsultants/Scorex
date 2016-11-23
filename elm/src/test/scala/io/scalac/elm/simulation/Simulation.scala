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

  private val log = LoggerFactory.getLogger(getClass)

  log.info(s"Random seed: ${simConfig.randomSeed}")
  private val random = new Random(simConfig.randomSeed)

  private val nodeManager = new NodeManager(simConfig)

  private def run(): SimResults = {
    using(nodeManager.initializeNodes()) { nodes =>

      log.info(s"Running simulation. ${simConfig.transactions.count} transactions to be made")

      val payments = runTransactions(nodes)
      val failed = awaitSynchronization(nodes, payments, simConfig.sync.attempts)
      log.info(s"${failed.size} transactions failed")

      val results = ResultCruncher(nodes, payments.filter(p => !failed(p.id)))

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

      Thread.sleep(simConfig.transactions.interval.toMillis)

      if (payment.isDefined && (payments.size % progressFreq == 0))
        log.info(s"Made ${payments.size} out of ${simConfig.transactions.count} transactions")
    }

    log.info(s"Made all ${payments.size} transactions")
    payments.toList
  }

  private def makePayment(nodes: Seq[NetworkNode]): Option[Payment] = {
    val Seq(sender, recipient) = random.shuffle(nodes).take(2)
    if (log.isDebugEnabled) {
      log.debug(s"Node ${sender.name} has ${sender.walletFunds()} funds")
    }
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

  private def awaitSynchronization(nodes: Seq[NetworkNode], payments: Seq[Payment], attemptsLeft: Int): Set[String] = {
    Thread.sleep(simConfig.sync.interval.toMillis)
    log.info(s"Awaiting blocktree synchronization, attempts left: $attemptsLeft")

    val txsNotIncluded = payments.map(_.id).toSet diff nodes.head.mainchain().flatMap(_.txs).map(_.id.base58).toSet
    val failed = nodes.flatMap(_.failed()).toSet
    val allTxs = txsNotIncluded.isEmpty
    val onlyFailedNotIncluded = failed == txsNotIncluded
    val sameLeaves = nodes.map(_.leaves().toSet).toSet.size == 1

    val inSync = (allTxs || onlyFailedNotIncluded) && sameLeaves

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
}
