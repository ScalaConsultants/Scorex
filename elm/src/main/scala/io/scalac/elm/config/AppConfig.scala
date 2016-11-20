package io.scalac.elm.config

import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigException.BadValue
import com.typesafe.config._
import io.circe.{Json, parser}
import io.scalac.elm.config.AppConfig._
import scorex.core.settings.Settings

import scala.concurrent.duration.{Duration, FiniteDuration}

object AppConfig {
  case class GenesisConf(generate: Boolean, initialFunds: Long, grains: Int)

  case class ConsensusConf(N: Int, confirmationDepth: Int, baseTarget: Long)

  sealed trait ForgingStrategyConf
  case class SimpleForgingStrategyConf(targetRatio: Double, minTxs: Int, maxTxs: Int) extends ForgingStrategyConf
  case object DumbForgingStrategyConf extends ForgingStrategyConf
  case class ForgingConf(delay: FiniteDuration, strategy: ForgingStrategyConf)


  def load(root: Config = ConfigFactory.load()): AppConfig = {
    val elm = root.getConfig("elm")

    AppConfig(
      settings = settings(root),
      genesis = genesis(elm.getConfig("genesis")),
      consensus = consensus(elm.getConfig("consensus")),
      forging = forging(elm.getConfig("forging"))
    )
  }

  private def settings(config: Config) = new Settings {
    val settingsJSON = config2Json(config.getObject("scorex"))
  }

  private def genesis(config: Config) = GenesisConf(
    generate = config.getBoolean("generate"),
    initialFunds = config.getLong("initial-funds"),
    grains = config.getInt("grains")
  )

  private def consensus(config: Config) = ConsensusConf(
    N = config.getInt("N"),
    confirmationDepth = config.getInt("confirmation-depth"),
    baseTarget = config.getLong("base-target")
  )

  private def forging(config: Config) = ForgingConf(
    delay = getDuration(config, "delay"),
    strategy = forgingStrategy(config)
  )

  private def forgingStrategy(config: Config) = config.getString("strategy") match {
    case "simple-forging-strategy" =>
      val strategyConf = config.getConfig("simple-forging-strategy")
      SimpleForgingStrategyConf(
        targetRatio = strategyConf.getDouble("target-ratio"),
        minTxs = strategyConf.getInt("min-transactions"),
        maxTxs = strategyConf.getInt("max-transactions")
      )

    case "dumb-forging-strategy" =>
      DumbForgingStrategyConf

    case other =>
      throw new BadValue(config.origin(), "strategy", s"Invalid value for strategy: $other")
  }

  private def config2Json(config: ConfigObject): Map[String, Json] = {
    val jsonStr = config.render(ConfigRenderOptions.concise)
    parser.parse(jsonStr).toTry.get.asObject.get.toMap
  }

  private def getDuration(config: Config, path: String): FiniteDuration =
    Duration(config.getDuration(path, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
}

case class AppConfig(
  settings: Settings,
  genesis: GenesisConf,
  consensus: ConsensusConf,
  forging: ForgingConf
)
