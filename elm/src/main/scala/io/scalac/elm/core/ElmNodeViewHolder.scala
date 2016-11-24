package io.scalac.elm.core

import cats.data.Xor
import io.scalac.elm.config.ElmConfig
import io.scalac.elm.core.ElmNodeViewHolder.{FullState, _}
import io.scalac.elm.forging.Forger
import io.scalac.elm.history.{ElmBlocktree, ElmSyncInfo}
import io.scalac.elm.state.{ElmMemPool, ElmMinState, ElmWallet}
import io.scalac.elm.transaction._
import io.scalac.elm.util._
import org.slf4j.LoggerFactory
import scorex.core.NodeViewHolder._
import scorex.core.NodeViewModifier.ModifierTypeId
import scorex.core.network.ConnectedPeer
import scorex.core.network.NodeViewSynchronizer.OtherNodeSyncingInfo
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.core.{NodeViewHolder, NodeViewModifier, NodeViewModifierCompanion}
import scorex.crypto.encode.Base58

import scala.collection.mutable

object ElmNodeViewHolder {

  case class PaymentRequest(address: String, amount: Long, fee: Long)
  case class PaymentRequestRelative(address: String, amountRatio: Double, fee: Long, minBalance: Long)
  case class PaymentResponse(id: Option[String], amount: Long)

  case object Forge

  case class FullState(minState: ElmMinState, wallet: ElmWallet, memPool: ElmMemPool)

  def zeroFullState(elmConfig: ElmConfig): FullState =
    FullState(ElmMinState(), ElmWallet.empty(elmConfig.node.keyPairSeed), ElmMemPool())
}

class ElmNodeViewHolder(forger: Forger, elmConfig: ElmConfig) extends {
  private val savedState: mutable.Map[ByteKey, FullState] = mutable.Map.empty
} with NodeViewHolder[PublicKey25519Proposition, ElmTransaction, ElmBlock] {

  import context.dispatcher

  override type SI = ElmSyncInfo

  override type HIS = ElmBlocktree
  override type MS = ElmMinState
  override type VL = ElmWallet
  override type MP = ElmMemPool

  type P = PublicKey25519Proposition
  type TX = ElmTransaction
  type PMOD = ElmBlock


  protected val log = LoggerFactory.getLogger(s"${getClass.getName}.${elmConfig.node.name}")
  protected val timeLogger = LoggerFactory.getLogger("timelogger." + elmConfig.node.name)

  val seenTxs = mutable.Set.empty[String]
  def printSeenTxs: String = seenTxs.mkString("\n")

  nodeView = genesisState

  scheduleForge()

  override lazy val modifierCompanions: Map[ModifierTypeId, NodeViewModifierCompanion[_ <: NodeViewModifier]] =
    Map(
      ElmBlock.ModifierTypeId -> ElmBlock,
      Transaction.ModifierTypeId -> ElmTransaction
    )

  override def receive: Receive =
    paymentRequest orElse forge orElse super.receive

  override def restoreState(): Option[(HIS, MS, VL, MP)] = None

  override protected def genesisState: (HIS, MS, VL, MP) = {

    val zeroBlocktree = ElmBlocktree.zero(elmConfig.consensus)
    val zeroFState = zeroFullState(elmConfig)
    val emptyMinState = zeroFState.minState
    val emptyWallet = zeroFState.wallet
    val emptyMemPool = zeroFState.memPool

    savedState += (ElmBlock.zero.id.key -> zeroFState)

    if (elmConfig.genesis.generate) {

      val genesisTx = createGenesisTx(emptyWallet.generator)

      val unsignedBlock: ElmBlock = ElmBlock(ElmBlock.zero.id, 0L, Array(), emptyWallet.generator, Seq(genesisTx)).updateHeights(1)
      val signature = PrivateKey25519Companion.sign(emptyWallet.secret, unsignedBlock.bytes)
      val genesisBlock: ElmBlock = unsignedBlock.copy(generationSignature = signature.signature)

      val blocktree = zeroBlocktree.append(genesisBlock, emptyMinState).toOption.get

      log.info(s"Genesis state with block ${genesisBlock.jsonNoTxs.noSpaces} created")

      val updatedMinState = emptyMinState.applyBlock(genesisBlock)
      val updatedWallet = emptyWallet.scanPersistent(genesisBlock)

      savedState += genesisBlock.id.key -> FullState(updatedMinState, updatedWallet, emptyMemPool)

      (blocktree, updatedMinState, updatedWallet, emptyMemPool)
    } else {
      (zeroBlocktree, emptyMinState, emptyWallet, emptyMemPool)
    }
  }

  private def createGenesisTx(defaultRecipient: PublicKey25519Proposition): ElmTransaction = {
    val initialAmount = elmConfig.genesis.totalFunds
    val grains = elmConfig.genesis.grains
    val addresses = elmConfig.genesis.distribution

    val recipients =
      if (addresses.isEmpty) Seq(defaultRecipient)
      else addresses.map(addr => PublicKey25519Proposition(Base58.decode(addr).get))
    val recipientsPerGrain = (0 to (grains / recipients.size)).flatMap(_ => recipients).take(grains)

    val outputs = recipientsPerGrain.map(TxOutput(initialAmount / grains, _))
    ElmTransaction(Nil, outputs.toList, 0)
  }

  override protected def pmodModify(block: ElmBlock, source: Option[ConnectedPeer]): Unit = logTimed("pmodModify"){
    notifySubscribers(
      EventType.StartingPersistentModifierApplication,
      StartingPersistentModifierApplication[P, TX, PMOD](block)
    )

    log.info(s"Applying modifier to nodeViewHolder: ${block.id.base58}")

    val parentId = block.parentId.key
    val parentState = savedState.getOrElse(parentId, zeroFullState(elmConfig))

    history().append(block, parentState.minState) match {
      case Xor.Right(newBlocktree) =>
        seenTxs ++= block.txs.drop(1).map(_.id.base58)
        updateState(newBlocktree, block)
        log.info(s"Persistent modifier ${Base58.encode(block.id)} applied successfully")
        notifySubscribers(EventType.SuccessfulPersistentModifier, SuccessfulModification[P, TX, PMOD](block, source))

      case Xor.Left(e) =>
        log.warn(s"Can`t apply persistent modifier (id: ${block.id.base58}, contents: $block) to history, reason: $e", e)
        notifySubscribers(EventType.FailedPersistentModifier, FailedModification[P, TX, PMOD](block, e, source))
    }
  }

  override def txModify(tx: ElmTransaction, source: Option[ConnectedPeer]): Unit = logTimed("txModify"){
    val updPool = memoryPool().applyTx(tx)
    seenTxs += tx.id.base58

    if (minimalState().isValid(tx)) {
      val updWallet = vault().scanOffchain(tx)
      nodeView = (history(), minimalState(), updWallet, updPool)
      log.debug(s"Unconfirmed transaction $tx added to the mempool")
      notifySubscribers(EventType.SuccessfulTransaction, SuccessfulTransaction[P, TX](tx, source))
    } else {
      log.warn(s"Failed ${if (source.isEmpty) "local" else "remote"} transaction: ${tx.id.base58}")
      if (source.isDefined) {
        // Failed transactions from remotes may occur when this node is behind. Those transactions may validate later
        // when new blocks are added. Still it would be good to remove those definitely failed ones at some point...
        nodeView = nodeView.copy(_4 = updPool)
      }
      notifySubscribers(EventType.FailedTransaction, FailedTransaction[P, TX](tx, "TX validation failure", source))
    }
  }

  override protected def compareSyncInfo: Receive = {
    case OtherNodeSyncingInfo(remote, syncInfo: ElmSyncInfo @unchecked) =>

      val extension = history().continuationIds(syncInfo).take(networkChunkSize)
      log.debug("sending extension: " + extension.map(_.base58).mkString(", "))

      sender() ! OtherNodeSyncingStatus(
        remote,
        history().compare(syncInfo),
        syncInfo,
        history().syncInfo(true),
        Some(extension.map(ElmBlock.ModifierTypeId -> _.array)).filterNot(_.isEmpty)
      )
  }

  private def updateState(newBlocktree: ElmBlocktree, newBlock: ElmBlock): Unit = {
    val blockId = newBlock.id.key
    val parentState = savedState(newBlock.parentId.key)
    val newMinState = parentState.minState.applyBlock(newBlock) //TODO: confirmation depth
    val newMemPool = memoryPool().merge(parentState.memPool).applyBlock(newBlock)
    val newWallet = parentState.wallet.scanPersistent(newBlock).scanOffchain(newMemPool.getAll) //TODO: confirmation depth

    savedState += blockId -> FullState(newMinState, newWallet, newMemPool)

    if (blockId == newBlocktree.maxLeaf.id) {
      nodeView = (newBlocktree, newMinState, newWallet, newMemPool)
    } else {
      nodeView = nodeView.copy(_1 = newBlocktree)
    }
  }

  private def scheduleForge(): Unit = {
    context.system.scheduler.scheduleOnce(elmConfig.forging.delay)(self ! Forge)
  }

  private def forge: Receive = {
    case Forge =>
      logTimed("forging") {
        forger.forge(history(), memoryPool(), savedState.toMap).foreach(pmodModify(_, None))
      }
      scheduleForge()
  }

  private def paymentRequest: Receive = {
    case PaymentRequest(address, amount, fee) =>
      val recipient = PublicKey25519Proposition(ByteKey.base58(address).array)
      val tx = vault().createPayment(recipient, amount, fee, history().height)
      sender ! PaymentResponse(tx.map(_.id.base58), amount)
      tx.foreach(txModify(_, None))

    case PaymentRequestRelative(address, amountRatio, fee, minBalance) =>
      val recipient = PublicKey25519Proposition(ByteKey.base58(address).array)
      val amount = (vault().balance * amountRatio).toLong
      if (vault().balance - amount - fee < minBalance)
        sender ! PaymentResponse(None, amount)
      else {
        val tx = vault().createPayment(recipient, amount, fee, history().height)
        sender ! PaymentResponse(tx.map(_.id.base58), amount)
        tx.foreach(txModify(_, None))
      }
  }
}
