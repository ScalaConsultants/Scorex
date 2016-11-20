package io.scalac.elm.forging

import io.scalac.elm.config.AppConfig.{DumbForgingStrategyConf, ForgingStrategyConf, SimpleForgingStrategyConf}
import io.scalac.elm.state.ElmMemPool

object ForgingStrategy {
  def apply(strategyConf: ForgingStrategyConf): ForgingStrategy = strategyConf match {
    case SimpleForgingStrategyConf(targetRatio, minTxs, maxTxs) =>
      SimpleForgingStrategy(targetRatio, minTxs, maxTxs)

    case DumbForgingStrategyConf =>
      DumbForgingStrategy
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

case object DumbForgingStrategy extends ForgingStrategy {
  def apply(availableCoinage: Long, targetScore: Long, memPool: ElmMemPool): Option[ForgeParams] =
    memPool.getAll.headOption
      .map(tx => ForgeParams(targetScore, Seq(tx)))
      .filter(_.coinAge <= availableCoinage)
}