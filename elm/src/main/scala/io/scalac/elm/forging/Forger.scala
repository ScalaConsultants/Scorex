package io.scalac.elm.forging

import akka.actor.{Actor, ActorRef}
import io.scalac.elm.config.AppConfig
import io.scalac.elm.history.ElmBlocktree
import io.scalac.elm.state.{ElmMemPool, ElmMinState, ElmWallet}
import io.scalac.elm.transaction._
import scorex.core.LocalInterface.LocallyGeneratedModifier
import scorex.core.NodeViewHolder.{CurrentView, GetCurrentView}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.core.utils.ScorexLogging

import scala.concurrent.duration._


object Forger {
  case object Forge
}

class Forger(viewHolderRef: ActorRef, appConfig: AppConfig) extends Actor with ScorexLogging {

  import Forger._
  import context.dispatcher

  //FIXME: should be part of consensus
  val MaxTransactionsInBlock = 100
  val MinTransactionsInBlock = 1
  //FIXME: should be part of consensus and dynamic
  val TargetScore = 100L

  val blockGenerationDelay = appConfig.forging.delay

  override def preStart(): Unit = {
    context.system.scheduler.scheduleOnce(1.second)(self ! Forge)
  }

  override def receive: Receive = {
    case CurrentView(history: ElmBlocktree, state: ElmMinState, wallet: ElmWallet, memPool: ElmMemPool) =>
      log.info("Trying to generate a new block, chain length: " + history.height)

      if (wallet.accumulatedCoinAge(history.height) >= TargetScore) {

        log.debug(s"MemPool has ${memPool.getAll.size} transactions")
        val toInclude = memPool.getAll.sortBy(_.fee)(Ordering[Long].reverse).take(MaxTransactionsInBlock)

        if (toInclude.size >= MinTransactionsInBlock) {
          log.debug(s"Including ${toInclude.size} transactions")
          val lastBlock = history.lastBlock
          val generators: Set[PublicKey25519Proposition] = Set(wallet.generator)
          val coinstake = wallet.createCoinstake(TargetScore, toInclude.map(_.fee).sum, history.height)

          val generatedBlocks = generators.map { generator =>
            val unsigned = ElmBlock(lastBlock.id, System.currentTimeMillis(), Array(), generator, coinstake +: toInclude)
            val signature = PrivateKey25519Companion.sign(wallet.secret, unsigned.bytes)
            val signedBlock = unsigned.copy(generationSignature = signature.signature)
            log.info(s"Generated new block: ${signedBlock.jsonNoTxs.noSpaces}")
            LocallyGeneratedModifier[PublicKey25519Proposition, ElmTransaction, ElmBlock](signedBlock)
          }
          generatedBlocks.foreach(localModifier => viewHolderRef ! localModifier)
        }
      }
      context.system.scheduler.scheduleOnce(blockGenerationDelay)(self ! Forge)

    case Forge =>
      viewHolderRef ! GetCurrentView
  }
}