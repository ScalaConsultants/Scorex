package io.scalac.elm.forging

import io.scalac.elm.config.ElmConfig.{DumbForgingStrategyConf, ForgingStrategyConf, SimpleForgingStrategyConf}
import io.scalac.elm.state.ElmMemPool

object ForgingStrategy {
  def apply(strategyConf: ForgingStrategyConf): ForgingStrategy = strategyConf match {
    case SimpleForgingStrategyConf(targetRatio, minTxs, maxTxs) =>
      SimpleForgingStrategy(targetRatio, minTxs, maxTxs)

    case DumbForgingStrategyConf(maxTxs) =>
      DumbForgingStrategy(maxTxs)
  }
}

sealed trait ForgingStrategy {

  /**
    * If conditions are met return ForgeParams specifying how much coinage to put into new block
    * and which transactions to include
    */
  def apply(availableCoinage: Long, targetScore: Long, memPool: ElmMemPool): Option[ForgeParams]
}

case class SimpleForgingStrategy(targetRatio: Double, minTxs: Int, maxTxs: Int) extends ForgingStrategy {
  def apply(availableCoinage: Long, targetScore: Long, memPool: ElmMemPool): Option[ForgeParams] = {
    val coinAge = math.ceil(targetScore * targetRatio).toLong
    if (coinAge > availableCoinage)
      None
    else {
      val transactions = memPool.getAll.toSeq.sortBy(_.fee)(Ordering[Long].reverse).take(maxTxs)
      Some(ForgeParams(coinAge, transactions)).filter(_.transactions.size >= minTxs)
    }
  }
}

case class DumbForgingStrategy(maxTxs: Int) extends ForgingStrategy {
  def apply(availableCoinage: Long, targetScore: Long, memPool: ElmMemPool): Option[ForgeParams] = {
    val txs = memPool.getAll.take(maxTxs).toSeq
    Some(ForgeParams(targetScore, txs)).filter(fp => fp.coinAge <= availableCoinage && txs.nonEmpty)
  }
}
