package io.scalac.elm.core

import cats.data.Xor
import io.scalac.elm.config.AppConfig
import io.scalac.elm.core.ElmNodeViewHolder.{FullState, _}
import io.scalac.elm.history.{ElmBlocktree, ElmSyncInfo}
import io.scalac.elm.state.{ElmMemPool, ElmMinState, ElmWallet}
import io.scalac.elm.transaction._
import io.scalac.elm.util._
import scorex.core.NodeViewHolder._
import scorex.core.NodeViewModifier.ModifierTypeId
import scorex.core.network.ConnectedPeer
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.core.{NodeViewHolder, NodeViewModifier, NodeViewModifierCompanion}
import scorex.crypto.encode.Base58

import scala.collection.mutable

object ElmNodeViewHolder {

  /**
    * Info about the current view with the history of FullStates included.
    * This probably exposes too much, but importantly fullStates are immutable.
    */
  case class FullView(history: ElmBlocktree, minState: ElmMinState, wallet: ElmWallet,
    memPool: ElmMemPool, fullStates: Map[ByteKey, FullState])
  case object GetFullView

  case class FullState(minState: ElmMinState, wallet: ElmWallet, memPool: ElmMemPool)
  val zeroFullState = FullState(ElmMinState(), ElmWallet(), ElmMemPool())
}

class ElmNodeViewHolder(appConfig: AppConfig) extends {
  private val state: mutable.Map[ByteKey, FullState] = mutable.Map.empty
} with NodeViewHolder[PublicKey25519Proposition, ElmTransaction, ElmBlock] {

  override type SI = ElmSyncInfo

  override type HIS = ElmBlocktree
  override type MS = ElmMinState
  override type VL = ElmWallet
  override type MP = ElmMemPool

  type P = PublicKey25519Proposition
  type TX = ElmTransaction
  type PMOD = ElmBlock

  override lazy val modifierCompanions: Map[ModifierTypeId, NodeViewModifierCompanion[_ <: NodeViewModifier]] =
    Map(
      ElmBlock.ModifierTypeId -> ElmBlock,
      Transaction.ModifierTypeId -> ElmTransaction
    )

  override def receive: Receive =
    getFullView orElse super.receive

  override def restoreState(): Option[(HIS, MS, VL, MP)] = None

  override protected def genesisState: (HIS, MS, VL, MP) = {

    val emptyMinState = zeroFullState.minState
    val emptyWallet = zeroFullState.wallet
    val emptyMemPool = zeroFullState.memPool

    state += (ElmBlock.zero.id.key -> zeroFullState)

    if (appConfig.genesis.generate) {
      val initialAmount = appConfig.genesis.initialFunds

      //TODO: configure
      // we generate a bunch of outputs because of coinage destruction problem
      // another way to approach this would be to retain age of coinstake change, but that would require outputs to be explicitly timestamped
      val grains = 10
      val genesisTx = ElmTransaction(Nil, List.fill(grains)(TxOutput(initialAmount / grains, emptyWallet.secret.publicImage)), 0, System.currentTimeMillis)

      val unsignedBlock: ElmBlock = ElmBlock(ElmBlock.zero.id, 0L, Array(), emptyWallet.generator, Seq(genesisTx))
      val signature = PrivateKey25519Companion.sign(emptyWallet.secret, unsignedBlock.bytes)
      val genesisBlock: ElmBlock = unsignedBlock.copy(generationSignature = signature.signature)

      val blocktree = ElmBlocktree.zero.append(genesisBlock, emptyMinState).toOption.get

      log.info(s"Genesis state with block ${genesisBlock.jsonNoTxs.noSpaces} created")

      val updatedMinState = emptyMinState.applyBlock(genesisBlock)
      val updatedWallet = emptyWallet.scanPersistent(genesisBlock)

      state += genesisBlock.id.key -> FullState(updatedMinState, updatedWallet, emptyMemPool)

      (blocktree, updatedMinState, updatedWallet, emptyMemPool)
    } else {
      (ElmBlocktree.zero, emptyMinState, emptyWallet, emptyMemPool)
    }
  }

  override protected def pmodModify(block: ElmBlock, source: Option[ConnectedPeer]): Unit = {
    notifySubscribers(
      EventType.StartingPersistentModifierApplication,
      StartingPersistentModifierApplication[P, TX, PMOD](block)
    )

    log.info(s"Applying modifier to nodeViewHolder: ${block.id.base58}")

    val parentId = block.parentId.key
    val parentState = state.getOrElse(parentId, zeroFullState)

    history().append(block, parentState.minState) match {
      case Xor.Right(newBlocktree) =>
        updateState(newBlocktree, block)

        log.info(s"Persistent modifier ${Base58.encode(block.id)} applied successfully")
        notifySubscribers(EventType.SuccessfulPersistentModifier, SuccessfulModification[P, TX, PMOD](block, source))

      case Xor.Left(e) =>
        log.warn(s"Can`t apply persistent modifier (id: ${block.id.base58}, contents: $block) to history, reason: $e", e)
        notifySubscribers(EventType.FailedPersistentModifier, FailedModification[P, TX, PMOD](block, e, source))
    }
  }

  override def txModify(tx: ElmTransaction, source: Option[ConnectedPeer]): Unit = {
    val updWallet = vault().scanOffchain(tx)
    memoryPool().applyTx(tx, minimalState()) match {
      case Xor.Right(updPool) =>
        log.debug(s"Unconfirmed transaction $tx added to the mempool")
        nodeView = (history(), minimalState(), updWallet, updPool)
        notifySubscribers(EventType.SuccessfulTransaction, SuccessfulTransaction[P, TX](tx, source))

      case Xor.Left(e) =>
        notifySubscribers(EventType.FailedTransaction, FailedTransaction[P, TX](tx, e, source))
    }
  }

  private def updateState(newBlocktree: ElmBlocktree, newBlock: ElmBlock): Unit = {
    val blockId = newBlock.id.key
    val parentState = state(newBlock.parentId.key)
    val newMinState = parentState.minState.applyBlock(newBlock) //TODO: confirmation depth
    val newWallet = parentState.wallet.scanPersistent(newBlock) //TODO: confirmation depth
    val newMemPool = parentState.memPool.applyBlock(newBlock)

    state += blockId -> FullState(newMinState, newWallet, newMemPool)

    if (blockId == newBlocktree.maxLeaf.id) {
      nodeView = (newBlocktree, newMinState, newWallet, newMemPool)
    } else {
      nodeView = nodeView.copy(_1 = newBlocktree)
    }
  }

  private def getFullView: Receive = {
    case GetFullView =>
      sender ! FullView(history(), minimalState(), vault(), memoryPool(), state.toMap)
  }
}
