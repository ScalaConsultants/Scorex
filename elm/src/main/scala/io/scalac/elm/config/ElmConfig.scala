package io.scalac.elm.config

import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigException.BadValue
import com.typesafe.config._
import io.circe.{Json, parser}
import io.scalac.elm.config.ElmConfig._
import scorex.core.app.ApplicationVersion
import scorex.core.settings.Settings
import scorex.crypto.encode.Base58

import scala.concurrent.duration.{Duration, FiniteDuration}

object ElmConfig {
  case class GenesisConf(generate: Boolean, initialFunds: Long, grains: Int)

  case class ConsensusConf(N: Int, confirmationDepth: Int, baseTarget: Long)

  sealed trait ForgingStrategyConf
  case class SimpleForgingStrategyConf(targetRatio: Double, minTxs: Int, maxTxs: Int) extends ForgingStrategyConf
  case class DumbForgingStrategyConf(maxTxs: Int) extends ForgingStrategyConf
  case class ForgingConf(delay: FiniteDuration, strategy: ForgingStrategyConf)

  case class NodeConf(appName: String = "elm", version: String = "1.0.0", name: String,
    shutdownHook: Boolean, keyPairSeed: Array[Byte]) {
    val appVersion = {
      val major :: minor :: rev :: Nil = version.split("\\.").toList.map(_.toInt)
      ApplicationVersion(major, minor, rev)
    }
  }


  def load(root: Config = ConfigFactory.load()): ElmConfig = {
    val elm = root.getConfig("elm")

    ElmConfig(
      scorexSettings = scorexSettings(root),
      node = node(elm.getConfig("node")),
      genesis = genesis(elm.getConfig("genesis")),
      consensus = consensus(elm.getConfig("consensus")),
      forging = forging(elm.getConfig("forging"))
    )
  }

  private def scorexSettings(config: Config) = new Settings {
    val settingsJSON = config2Json(config.getObject("scorex"))
  }

  private def node(config: Config) = NodeConf(
    appName = config.getString("app-name"),
    version = config.getString("version"),
    name = config.getString("name"),
    shutdownHook = config.getBoolean("shutdown-hook"),
    keyPairSeed = Base58.decode(config.getString("key-pair-seed")).get
  )

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
      val strategyConf = config.getConfig("dumb-forging-strategy")
      DumbForgingStrategyConf(
        maxTxs = strategyConf.getInt("max-transactions")
      )

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

case class ElmConfig(
  scorexSettings: Settings,
  node: NodeConf,
  genesis: GenesisConf,
  consensus: ConsensusConf,
  forging: ForgingConf
)
