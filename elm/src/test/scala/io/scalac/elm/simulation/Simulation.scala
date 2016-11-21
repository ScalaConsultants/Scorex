package io.scalac.elm.simulation

import io.circe.generic.auto._
import io.circe.syntax._
import io.scalac.elm.config.SimConfig
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

  private val payments = mutable.ListBuffer.empty[Payment]

  private def run(): SimResults = {
    using(nodeManager.initializeNodes()) { nodes =>

      log.info(s"Running simulation. ${simConfig.transactions.count} transactions to be made")

      runTransactions(nodes)
      awaitSynchronization(nodes, payments, simConfig.sync.attempts)

      val results = ResultCruncher(nodes, payments.toList)

      log.info("Simulation finished")
      results

    }() { nodes =>
      if (simConfig.shutdownNodes)
        nodeManager.shutdownNodes(nodes)
    }
  }

  private def runTransactions(nodes: Seq[NetworkNode]): Unit = {
    val progressFreq = 10

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
  }

  private def makePayment(nodes: Seq[NetworkNode]): Option[Payment] = {
    val Seq(sender, recipient) = random.shuffle(nodes).take(2)
    val funds = sender.walletFunds()
    log.debug(s"Node ${sender.name} has $funds funds")

    if (funds > simConfig.transactions.minFunds) {
      val amount = randomAmount(funds)
      val fee = randomFee()

      val maybeId = sender.makePayment(recipient.publicKey, amount, fee)
      maybeId.map(Payment(_, sender.publicKey, recipient.publicKey, amount, fee))
    } else None
  }

  private def randomAmount(funds: Long): Long = {
    val min = simConfig.transactions.minAmount
    val max = simConfig.transactions.maxAmount
    val ratio = min + random.nextDouble() * (max - min)
    math.max(1L, (funds * ratio).toLong)
  }

  private def randomFee(): Long = {
    val min = simConfig.transactions.minFee
    val max = simConfig.transactions.maxFee
    min + math.abs(random.nextLong()) % (max - min + 1)
  }

  private def awaitSynchronization(nodes: Seq[NetworkNode], payments: Seq[Payment], attemptsLeft: Int): Unit = {
    Thread.sleep(simConfig.sync.interval.toMillis)
    log.info(s"Awaiting blocktree synchronization, attempts left: $attemptsLeft")

    val txNotIncluded = payments.map(_.id).toSet diff nodes.head.mainchain().flatMap(_.txs).map(_.id.base58).toSet
    val allTxs = txNotIncluded.isEmpty
    val sameLeaves = nodes.map(_.leaves().toSet).toSet.size == 1

    val inSync = allTxs && sameLeaves

    if (!inSync) {
      if (attemptsLeft == 0) {
        val sameMainchains = nodes.map(_.mainchainIds()).toSet.size == 1
        log.info(s"Number of transactions not included: ${txNotIncluded.size}. Mainchains are the same: $sameMainchains")
        log.error("Nodes failed to synchronize blocktrees!")
      }
      else
        awaitSynchronization(nodes, payments, attemptsLeft - 1)
    }
  }

}
