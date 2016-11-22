package io.scalac.elm.core

import cats.data.Xor
import io.scalac.elm.config.ElmConfig
import io.scalac.elm.core.ElmNodeViewHolder.{FullState, _}
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

  /**
    * Info about the current view with the history of FullStates included.
    * This probably exposes too much, but importantly fullStates are immutable.
    */
  case class FullView(history: ElmBlocktree, minState: ElmMinState, wallet: ElmWallet,
    memPool: ElmMemPool, fullStates: Map[ByteKey, FullState])
  case object GetFullView

  case object GetWalletForTransaction
  case class WalletForTransaction(wallet: Option[ElmWallet], currentHeight: Int)
  case class ReturnWallet(tx: Option[ElmTransaction])

  case class FullState(minState: ElmMinState, wallet: ElmWallet, memPool: ElmMemPool)

  def zeroFullState(elmConfig: ElmConfig): FullState =
    FullState(ElmMinState(), ElmWallet.empty(elmConfig.node.keyPairSeed), ElmMemPool())
}

class ElmNodeViewHolder(elmConfig: ElmConfig) extends {
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


  val log = LoggerFactory.getLogger(s"${getClass.getName}.${elmConfig.node.name}")

  nodeView = genesisState

  private var walletLocked = false

  override lazy val modifierCompanions: Map[ModifierTypeId, NodeViewModifierCompanion[_ <: NodeViewModifier]] =
    Map(
      ElmBlock.ModifierTypeId -> ElmBlock,
      Transaction.ModifierTypeId -> ElmTransaction
    )

  override def receive: Receive =
    getFullView orElse getWalletForTx orElse walletReturned orElse super.receive

  override def restoreState(): Option[(HIS, MS, VL, MP)] = None

  override protected def genesisState: (HIS, MS, VL, MP) = {

    val zeroBlocktree = ElmBlocktree.zero(elmConfig.consensus)
    val zeroFState = zeroFullState(elmConfig)
    val emptyMinState = zeroFState.minState
    val emptyWallet = zeroFState.wallet
    val emptyMemPool = zeroFState.memPool

    state += (ElmBlock.zero.id.key -> zeroFState)

    if (elmConfig.genesis.generate) {
      val initialAmount = elmConfig.genesis.initialFunds

      // we generate a bunch of outputs because of coinage destruction problem
      // another way to approach this would be to retain age of coinstake change, but that would require outputs to be explicitly timestamped
      val grains = elmConfig.genesis.grains
      val genesisTx = ElmTransaction(Nil, List.fill(grains)(TxOutput(initialAmount / grains, emptyWallet.secret.publicImage)), 0)

      val unsignedBlock: ElmBlock = ElmBlock(ElmBlock.zero.id, 0L, Array(), emptyWallet.generator, Seq(genesisTx)).updateHeights(1)
      val signature = PrivateKey25519Companion.sign(emptyWallet.secret, unsignedBlock.bytes)
      val genesisBlock: ElmBlock = unsignedBlock.copy(generationSignature = signature.signature)

      val blocktree = zeroBlocktree.append(genesisBlock, emptyMinState).toOption.get

      log.info(s"Genesis state with block ${genesisBlock.jsonNoTxs.noSpaces} created")

      val updatedMinState = emptyMinState.applyBlock(genesisBlock)
      val updatedWallet = emptyWallet.scanPersistent(genesisBlock)

      state += genesisBlock.id.key -> FullState(updatedMinState, updatedWallet, emptyMemPool)

      (blocktree, updatedMinState, updatedWallet, emptyMemPool)
    } else {
      (zeroBlocktree, emptyMinState, emptyWallet, emptyMemPool)
    }
  }

  override protected def pmodModify(block: ElmBlock, source: Option[ConnectedPeer]): Unit = {
    notifySubscribers(
      EventType.StartingPersistentModifierApplication,
      StartingPersistentModifierApplication[P, TX, PMOD](block)
    )

    log.info(s"Applying modifier to nodeViewHolder: ${block.id.base58}")

    val parentId = block.parentId.key
    val parentState = state.getOrElse(parentId, zeroFullState(elmConfig))

    history().append(block, parentState.minState) match {
      case Xor.Right(newBlocktree) =>
        updateState(newBlocktree, block)
        if (vault().balance <= 0 && elmConfig.node.name == "alice") {
          log.warn(s"Wallet has ${vault().balance} after applying block: \n ${block.json.spaces4}")
        }

        log.info(s"Persistent modifier ${Base58.encode(block.id)} applied successfully")
        notifySubscribers(EventType.SuccessfulPersistentModifier, SuccessfulModification[P, TX, PMOD](block, source))

      case Xor.Left(e) =>
        log.warn(s"Can`t apply persistent modifier (id: ${block.id.base58}, contents: $block) to history, reason: $e", e)
        notifySubscribers(EventType.FailedPersistentModifier, FailedModification[P, TX, PMOD](block, e, source))
    }
  }

  override def txModify(tx: ElmTransaction, source: Option[ConnectedPeer]): Unit = {
    memoryPool().applyTx(tx, minimalState()) match {
      case Xor.Right(updPool) =>
        val updWallet = vault().scanOffchain(tx)
        nodeView = (history(), minimalState(), updWallet, updPool)
        log.debug(s"Unconfirmed transaction $tx added to the mempool")
        if (updWallet.balance <= 0 && elmConfig.node.name == "alice") {
          log.warn(s"Wallet has ${updWallet.balance} after applying offchain tx: \n ${tx.json.spaces4}")
        }
        notifySubscribers(EventType.SuccessfulTransaction, SuccessfulTransaction[P, TX](tx, source))

      case Xor.Left(e) =>
        if (true || source.isEmpty) {
          log.info(s"Unapplying failed transaction: ${tx.id.base58}")
          nodeView = nodeView.copy(_3 = vault().unscanFailed(tx, minimalState()))
        }
        notifySubscribers(EventType.FailedTransaction, FailedTransaction[P, TX](tx, e, source))
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
    val parentState = state(newBlock.parentId.key)
    val newMinState = parentState.minState.applyBlock(newBlock) //TODO: confirmation depth
    val newMemPool = memoryPool().merge(parentState.memPool).applyBlock(newBlock).filterValid(newMinState)
    val newWallet = parentState.wallet.scanPersistent(newBlock).scanOffchain(newMemPool.getAll) //TODO: confirmation depth

    if (elmConfig.node.name == "alice") {
      import io.circe.syntax._
      import io.circe.generic.auto._


      val outSum = newWallet.chainTxOutputs.values.map(_.value).sum
      println(s"WALLET BALANCE ALICE: ${newWallet.balance} vs $outSum")

      val aliceOuts = newBlocktree.chainOf(newBlock.id.key).toList.flatMap(_.block.txs).flatMap(_.outputs).filter(_.proposition.pubKeyBytes.base58 == newWallet.generator.pubKeyBytes.base58)
      val aliceIns = newBlocktree.chainOf(newBlock.id.key).toList.flatMap(_.block.txs).flatMap(_.inputs).map(_.closedBoxId.key).toSet

      val treeOuts = aliceOuts.filterNot(out => aliceIns(out.id.key))
      val waltOuts = newWallet.chainTxOutputs.values.toSeq

      println(s"TREE OUTS: ${treeOuts.size}, sum: ${treeOuts.map(_.value).sum}")
      println(s"WALT OUTS: ${waltOuts.size}, sum: ${waltOuts.map(_.value).sum}")

      val outs = newBlocktree.chainOf(newBlock.id.key).toList.flatMap(_.block.txs).flatMap(_.outputs).map(_.id.key)
      val ins = newBlocktree.chainOf(newBlock.id.key).toList.flatMap(_.block.txs).flatMap(_.inputs).map(_.closedBoxId.key)
      println(s"chain has duplicate outs: " + (outs.size != outs.toSet.size))
      println(s"chain has duplicate ins: " + (ins.size != ins.toSet.size))

      val allOuts = newBlocktree.chainOf(newBlock.id.key).toList.flatMap(_.block.txs).flatMap(_.outputs)
      val allIns = newBlocktree.chainOf(newBlock.id.key).toList.flatMap(_.block.txs).flatMap(_.inputs).map(_.closedBoxId.key).toSet
      val unspentOuts = allOuts.filterNot(o => allIns(o.id.key))
      println(s"All unspent outs sum up to: ${unspentOuts.map(_.value).sum}")

      val allTxs = newBlocktree.chainOf(newBlock.id.key).toList.flatMap(_.block.txs)
      val allTxIds = allTxs.map(_.id.key)
      val duplicateTxs = allTxIds.size != allTxIds.toSet.size
      println("chain has duplicate transactions: " + duplicateTxs)
      if (duplicateTxs)
        println(newBlocktree.chainOf(newBlock.id.key).toList.map(_.block.json).asJson.spaces4)
    }

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

  private def getWalletForTx: Receive = {
    case GetWalletForTransaction =>
      if (walletLocked)
        sender ! WalletForTransaction(None, 0)
      else
        walletLocked = true
        sender ! WalletForTransaction(Some(vault()), history().height)
  }

  private def walletReturned: Receive = {
    case ReturnWallet(tx) =>
      tx.foreach(txModify(_, None))
      walletLocked = false
  }
}
