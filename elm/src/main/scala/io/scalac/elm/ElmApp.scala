package io.scalac.elm

import akka.actor.Props
import io.scalac.elm.api.{BlocktreeApiRoute, WalletApiRoute}
import io.scalac.elm.config.{AppConfig, AppInfo}
import io.scalac.elm.history.{ElmSyncInfo, ElmSyncInfoSpec}
import io.scalac.elm.core.{ElmLocalInterface, ElmNodeViewHolder}
import io.scalac.elm.forging.Forger
import io.scalac.elm.transaction.{ElmBlock, ElmTransaction}
import scorex.core.api.http._
import scorex.core.app.Application
import scorex.core.network.NodeViewSynchronizer
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging

import scala.reflect.runtime.universe.typeOf

class ElmApp(appInfo: AppInfo, appConfig: AppConfig) extends {

  override val applicationName = appInfo.name
  override val appVersion = appInfo.appVersion
  override val settings = appConfig.settings
  override protected val additionalMessageSpecs = Seq(ElmSyncInfoSpec)

} with Application {

  override type P = PublicKey25519Proposition
  override type TX = ElmTransaction
  override type PMOD = ElmBlock
  override type NVHT = ElmNodeViewHolder

  override val nodeViewHolderRef = actorSystem.actorOf(Props(classOf[ElmNodeViewHolder], appConfig))
  override val localInterface = actorSystem.actorOf(Props(classOf[ElmLocalInterface], nodeViewHolderRef))
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

  val forger = actorSystem.actorOf(Props(classOf[Forger], nodeViewHolderRef, appConfig))
}

object ElmApp extends App with ScorexLogging {
  val elmApp = new ElmApp(AppInfo(), AppConfig.load())
  elmApp.run()
}
