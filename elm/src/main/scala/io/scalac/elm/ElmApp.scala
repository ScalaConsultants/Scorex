package io.scalac.elm

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import io.scalac.elm.api.{BlocktreeApiRoute, WalletApiRoute}
import io.scalac.elm.config.ElmConfig
import io.scalac.elm.history.{ElmSyncInfo, ElmSyncInfoSpec}
import io.scalac.elm.core.{ElmLocalInterface, ElmNodeViewHolder}
import io.scalac.elm.forging.Forger
import io.scalac.elm.transaction.{ElmBlock, ElmTransaction}
import scorex.core.api.http._
import scorex.core.app.Application
import scorex.core.network.{NetworkController, NodeViewSynchronizer}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition

import scala.reflect.runtime.universe.typeOf

case class ElmApp(elmConfig: ElmConfig) extends {
  override val applicationName = elmConfig.node.appName
  override val appVersion = elmConfig.node.appVersion
  override val settings = elmConfig.scorexSettings
  override protected val additionalMessageSpecs = Seq(ElmSyncInfoSpec)

} with Application {

  override implicit lazy val actorSystem = ActorSystem(s"${elmConfig.node.appName}-${elmConfig.node.name}")
  val nodeName: String = elmConfig.node.name

  override type P = PublicKey25519Proposition
  override type TX = ElmTransaction
  override type PMOD = ElmBlock
  override type NVHT = ElmNodeViewHolder

  override val nodeViewHolderRef = actorSystem.actorOf(Props(classOf[ElmNodeViewHolder], elmConfig))
  override val localInterface = actorSystem.actorOf(Props(classOf[ElmLocalInterface], nodeViewHolderRef, elmConfig))
  override val nodeViewSynchronizer = actorSystem.actorOf(
    Props(classOf[NodeViewSynchronizer[P, TX, ElmSyncInfo, ElmSyncInfoSpec.type]],
      networkController, nodeViewHolderRef, localInterface, ElmSyncInfoSpec))

  override val apiRoutes = Seq(
    UtilsApiRoute(settings),
    PeersApiRoute(peerManager, networkController, settings),
    new WalletApiRoute(settings, nodeViewHolderRef),
    new BlocktreeApiRoute(settings, nodeViewHolderRef)
  )

  override val apiTypes = Seq(
    typeOf[UtilsApiRoute],
    typeOf[PeersApiRoute],
    typeOf[WalletApiRoute],
    typeOf[BlocktreeApiRoute]
  )

  val forger = actorSystem.actorOf(Props(classOf[Forger], nodeViewHolderRef, elmConfig))

  override def run(): Unit = {
    log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
    log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")
    log.debug(s"RPC is allowed at 0.0.0.0:${settings.rpcPort}")

    Http().bindAndHandle(combinedRoute, "0.0.0.0", settings.rpcPort)

    if (elmConfig.node.shutdownHook) {
      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run() {
          log.error("Unexpected shutdown")
          stopAll()
        }
      })
    }
  }

  override def stopAll(): Unit = synchronized {
    log.info("Stopping network services")
    if (settings.upnpEnabled) upnp.deletePort(settings.port)
    networkController ! NetworkController.ShutdownNetwork

    log.info("Stopping actors (incl. block generator)")
    actorSystem.terminate()
  }
}

object ElmApp extends App {
  val elmApp = ElmApp(ElmConfig.load())
  elmApp.run()
}
