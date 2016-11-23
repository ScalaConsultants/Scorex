package io.scalac.elm.forging

import io.scalac.elm.config.ElmConfig
import io.scalac.elm.core.ElmNodeViewHolder.FullState
import io.scalac.elm.history.ElmBlocktree
import io.scalac.elm.state.ElmMemPool
import io.scalac.elm.transaction._
import io.scalac.elm.util.ByteKey
import org.slf4j.LoggerFactory
import scorex.core.transaction.state.PrivateKey25519Companion

class Forger(elmConfig: ElmConfig) {

  private val log = LoggerFactory.getLogger(s"${getClass.getName}.${elmConfig.node.name}")

  private val strategy = ForgingStrategy(elmConfig.forging.strategy)
  private val blockGenerationDelay = elmConfig.forging.delay

  def forge(history: ElmBlocktree, currentMemPool: ElmMemPool, fullStates: Map[ByteKey, FullState]): List[ElmBlock] = {
    log.info("Trying to generate new blocks, main chain length: " + history.height)

    for {
      leafId <- history.leaves.toList

      parent = history.blocks(leafId)
      FullState(savedMinState, savedWallet, savedMemPool) = fullStates(leafId)
      memPool = savedMemPool.merge(currentMemPool).filterValid(savedMinState)
      wallet = savedWallet.scanOffchain(memPool.getAll)
      availableCoinage = wallet.accumulatedCoinAge(parent.height)
      target = history.targetScore(leafId)

      ForgeParams(coinAge, transactions) <- strategy(availableCoinage, target, memPool)
    }
    yield {
      val coinstake = wallet.createCoinstake(coinAge, transactions.map(_.fee).sum, parent.height)
      val unsigned = ElmBlock(parent.id.array, System.currentTimeMillis(), Array(), wallet.generator,
        coinstake +: transactions).updateHeights(parent.height + 1)
      val signature = PrivateKey25519Companion.sign(wallet.secret, unsigned.bytes)
      val signedBlock = unsigned.copy(generationSignature = signature.signature)

      log.info(s"Generated new block: ${signedBlock.jsonNoTxs.noSpaces}")
      signedBlock
    }
  }
}
