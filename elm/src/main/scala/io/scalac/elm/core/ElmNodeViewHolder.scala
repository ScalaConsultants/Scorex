package io.scalac.elm.core

import io.scalac.elm.config.AppConfig
import io.scalac.elm.consensus.{ElmBlockchain, ElmSyncInfo}
import io.scalac.elm.state.ElmWallet.TimedTxOutput
import io.scalac.elm.state.{ElmMemPool, ElmMinState, ElmWallet}
import io.scalac.elm.transaction._
import io.scalac.elm.util._
import scorex.core.NodeViewModifier.ModifierTypeId
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.{NodeViewHolder, NodeViewModifier, NodeViewModifierCompanion}

import scala.util.{Failure, Success}

class ElmNodeViewHolder(appConfig: AppConfig) extends NodeViewHolder[PublicKey25519Proposition, ElmTransaction, ElmBlock] {
  override type SI = ElmSyncInfo

  override type HIS = ElmBlockchain
  override type MS = ElmMinState
  override type VL = ElmWallet
  override type MP = ElmMemPool

  override lazy val modifierCompanions: Map[ModifierTypeId, NodeViewModifierCompanion[_ <: NodeViewModifier]] = ???

  override def restoreState(): Option[(HIS, MS, VL, MP)] = None

  override protected def genesisState: (HIS, MS, VL, MP) =
    if (appConfig.genesis.generate) {
      val emptyBlockchain = ElmBlockchain()
      val emptyState = ElmMinState()
      val emptyWallet = ElmWallet()

      val zeroSignature = Array.fill(32)(0.toByte)
      val initialAmount = appConfig.genesis.initialFunds
      val generator = PublicKey25519Proposition(zeroSignature)
      // we generate a bunch of outputs because of coinage destruction problem
      // another way to approach this would be to retain age of coinstake change, but that would require outputs to be explicitly timestamped
      val genesisTx = ElmTransaction(Nil, List.fill(initialAmount.toInt)(TxOutput(1, emptyWallet.secret.publicImage)), 0, System.currentTimeMillis)
      val updatedWallet = emptyWallet.scan(genesisTx, offchain = false)

      /*  copy(
        chainTxOutputs = genesisTx.outputs.map(out => out.id.key -> TimedTxOutput(out, genesisTx.timestamp)).toMap,
        currentBalance = initialAmount
      )*/

      val genesisBlock: ElmBlock = ElmBlock(zeroSignature, 0L, zeroSignature, generator, Seq(genesisTx))
      val blockchain = emptyBlockchain.append(genesisBlock) match {
        case Failure(f) => throw f
        case Success(newBlockchain) => newBlockchain._1
      }
      require(blockchain.height() == 1, s"${blockchain.height()} == 1")

      val state = emptyState.applyModifier(genesisBlock) match {
        case Failure(f) => throw f
        case Success(newState) => newState
      }
      require(!state.isEmpty)

      log.info(s"Genesis state with block ${genesisBlock.jsonNoTxs.noSpaces} created")

      (blockchain, state, updatedWallet, new ElmMemPool())
    } else {
      (ElmBlockchain(), ElmMinState(), ElmWallet(), new ElmMemPool)
    }
}
