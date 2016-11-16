package io.scalac.elm.core

import io.scalac.elm.config.AppConfig
import io.scalac.elm.history.{ElmBlocktree, ElmSyncInfo}
import io.scalac.elm.state.{ElmMemPool, ElmMinState, ElmWallet}
import io.scalac.elm.transaction._
import io.scalac.elm.util._
import scorex.core.NodeViewModifier.ModifierTypeId
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.core.{NodeViewHolder, NodeViewModifier, NodeViewModifierCompanion}

class ElmNodeViewHolder(appConfig: AppConfig) extends NodeViewHolder[PublicKey25519Proposition, ElmTransaction, ElmBlock] {
  override type SI = ElmSyncInfo

  override type HIS = ElmBlocktree
  override type MS = ElmMinState
  override type VL = ElmWallet
  override type MP = ElmMemPool

  override lazy val modifierCompanions: Map[ModifierTypeId, NodeViewModifierCompanion[_ <: NodeViewModifier]] =
    Map(
      ElmBlock.ModifierTypeId -> ElmBlock,
      Transaction.ModifierTypeId -> ElmTransaction
    )

  override def restoreState(): Option[(HIS, MS, VL, MP)] = None

  override protected def genesisState: (HIS, MS, VL, MP) = {

    val zeroFullState = ElmBlocktree.zero.state(ElmBlock.zero.id.key)
    val emptyMinState = zeroFullState.minState
    val emptyWallet = zeroFullState.wallet
    val emptyMemPool = zeroFullState.memPool

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

      val blocktree = ElmBlocktree.zero.append(genesisBlock, emptyMemPool)
      val fullState = blocktree.fullState

      log.info(s"Genesis state with block ${genesisBlock.jsonNoTxs.noSpaces} created")

      (blocktree, fullState.minState, fullState.wallet, fullState.memPool)
    } else {
      (ElmBlocktree.zero, ElmMinState(), ElmWallet(), new ElmMemPool)
    }
  }
}
