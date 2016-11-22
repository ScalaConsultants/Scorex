package io.scalac.elm.core

import akka.actor.ActorRef
import io.scalac.elm.config.ElmConfig
import io.scalac.elm.transaction.{ElmBlock, ElmTransaction}
import io.scalac.elm.util.ByteKey
import org.slf4j.LoggerFactory
import scorex.core.LocalInterface
import scorex.core.transaction.box.proposition.PublicKey25519Proposition

class ElmLocalInterface(override val viewHolderRef: ActorRef, elmConfig: ElmConfig)
  extends LocalInterface[PublicKey25519Proposition, ElmTransaction, ElmBlock] {

  override val log = LoggerFactory.getLogger(s"${getClass.getName}.${elmConfig.node.name}")

  override protected def onStartingPersistentModifierApplication(pmod: ElmBlock): Unit =
    log.debug(s"onStartingPersistentModifierApplication: ${pmod.id.base58}")

  override protected def onFailedTransaction(tx: ElmTransaction): Unit =
    log.warn(s"onFailedTransaction: ${tx.id.base58}")

  override protected def onFailedModification(mod: ElmBlock): Unit =
    log.warn(s"onFailedModification: ${mod.id.base58}")

  override protected def onSuccessfulTransaction(tx: ElmTransaction): Unit =
    log.debug(s"onSuccessfulTransaction: ${tx.id.base58}")

  override protected def onSuccessfulModification(mod: ElmBlock): Unit =
    log.debug(s"onSuccessfulModification: ${mod.id.base58}")

  override protected def onNoBetterNeighbour(): Unit = ()

  override protected def onBetterNeighbourAppeared(): Unit = ()
}
