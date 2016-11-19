package io.scalac.elm.forging

import akka.actor.{Actor, ActorRef}
import io.scalac.elm.config.AppConfig
import io.scalac.elm.core.ElmNodeViewHolder.{FullView, GetFullView}
import io.scalac.elm.transaction._
import scorex.core.LocalInterface.LocallyGeneratedModifier
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

  //TODO: configure
  val strategy: ForgingStrategy = SimpleForgingStrategy(1.5, 1, 10)

  val blockGenerationDelay = appConfig.forging.delay

  override def preStart(): Unit = {
    context.system.scheduler.scheduleOnce(1.second)(self ! Forge)(context.dispatcher)
  }

  override def receive: Receive = {
    case FullView(history, minState, currentWallet, currentMemPool, fullStates) =>
      log.info("Trying to generate new blocks, main chain length: " + history.height)

      for {
        leafId <- history.leaves

        parent = history.blocks(leafId)
        fullState = fullStates(parent.id)
        memPool = fullState.memPool.merge(currentMemPool)
        wallet = currentWallet.scanOffchain(memPool.getAll)
        availableCoinage = wallet.accumulatedCoinAge(parent.height)
        target = history.targetScore(leafId)

        ForgeParams(coinAge, transactions) <- strategy(availableCoinage, target, memPool)
      } {
        val coinstake = wallet.createCoinstake(coinAge, transactions.map(_.fee).sum, parent.height)
        val unsigned = ElmBlock(parent.id.array, System.currentTimeMillis(), Array(), wallet.generator, coinstake +: transactions)
        val signature = PrivateKey25519Companion.sign(wallet.secret, unsigned.bytes)
        val signedBlock = unsigned.copy(generationSignature = signature.signature)

        log.info(s"Generated new block: ${signedBlock.jsonNoTxs.noSpaces}")

        val modifier = LocallyGeneratedModifier[PublicKey25519Proposition, ElmTransaction, ElmBlock](signedBlock)
        viewHolderRef ! modifier
      }

      context.system.scheduler.scheduleOnce(blockGenerationDelay)(self ! Forge)

    case Forge =>
      viewHolderRef ! GetFullView
  }
}