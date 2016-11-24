package io.scalac.elm.config

import java.util.concurrent.TimeUnit

import com.typesafe.config.{Config, ConfigFactory}
import io.scalac.elm.config.SimConfig.{HttpConf, SyncConf, TxConf}

import scala.concurrent.duration.{Duration, FiniteDuration}

object SimConfig {
  case class HttpConf(timeout: FiniteDuration)

  case class TxConf(count: Int, interval: FiniteDuration, minFunds: Long, minAmount: Double, maxAmount: Double, minFee: Long, maxFee: Long)

  case class SyncConf(interval: FiniteDuration, attempts: Int)

  def load(root: Config = ConfigFactory.parseResources("simulation-common.conf")): SimConfig = {
    val sim = root.getConfig("simulation")

    SimConfig(
      networkSize = sim.getInt("network-size"),
      randomSeed = randomSeed(sim),
      shutdownNodes = sim.getBoolean("shutdown-nodes"),
      transactions = transactions(sim.getConfig("transactions")),
      http = http(sim.getConfig("http")),
      sync = sync(sim.getConfig("synchronization"))
    )
  }

  private def randomSeed(config: Config) = {
    val seed = config.getLong("random-seed")
    if (seed == 0) System.currentTimeMillis() else seed
  }

  private def transactions(config: Config) = TxConf(
    count = config.getInt("count"),
    interval = getDuration(config, "interval"),
    minFunds = config.getLong("min-funds"),
    minAmount = config.getDouble("min-amount"),
    maxAmount = config.getDouble("max-amount"),
    minFee = config.getLong("min-fee"),
    maxFee = config.getLong("max-fee")
  )

  private def http(config: Config) = HttpConf(
    timeout = getDuration(config, "timeout")
  )

  private def sync(config: Config) = SyncConf(
    interval = getDuration(config, "interval"),
    attempts = config.getInt("attempts")
  )


  private def getDuration(config: Config, path: String): FiniteDuration =
    Duration(config.getDuration(path, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
}

case class SimConfig(
  networkSize: Int,
  randomSeed: Long,
  shutdownNodes: Boolean,
  transactions: TxConf,
  http: HttpConf,
  sync: SyncConf
)
