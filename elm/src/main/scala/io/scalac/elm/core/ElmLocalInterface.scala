package io.scalac.elm.core

import akka.actor.ActorRef
import io.scalac.elm.transaction.{ElmBlock, ElmTransaction}
import io.scalac.elm.util.ByteKey
import scorex.core.LocalInterface
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging

class ElmLocalInterface(override val viewHolderRef: ActorRef)
  extends LocalInterface[PublicKey25519Proposition, ElmTransaction, ElmBlock] with ScorexLogging {

  override protected def onStartingPersistentModifierApplication(pmod: ElmBlock): Unit =
    log.debug(s"onStartingPersistentModifierApplication: ${pmod.id.base58}")

  override protected def onFailedTransaction(tx: ElmTransaction): Unit =
    log.debug(s"onFailedTransaction: ${tx.id.base58}")

  override protected def onFailedModification(mod: ElmBlock): Unit =
    log.debug(s"onFailedModification: ${mod.id.base58}")

  override protected def onSuccessfulTransaction(tx: ElmTransaction): Unit =
    log.debug(s"onSuccessfulTransaction: ${tx.id.base58}")

  override protected def onSuccessfulModification(mod: ElmBlock): Unit =
    log.debug(s"onSuccessfulModification: ${mod.id.base58}")

  override protected def onNoBetterNeighbour(): Unit = ()

  override protected def onBetterNeighbourAppeared(): Unit = ()
}
