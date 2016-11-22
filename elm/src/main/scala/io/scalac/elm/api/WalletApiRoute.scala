package io.scalac.elm.api

import javax.ws.rs.{Path, Produces}

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import io.scalac.elm.core.ElmNodeViewHolder.{GetWalletForTransaction, ReturnWallet, WalletForTransaction}
import io.scalac.elm.history.ElmBlocktree
import io.scalac.elm.state.{ElmMemPool, ElmMinState, ElmWallet}
import io.scalac.elm.util.ByteKey
import io.swagger.annotations._
import scorex.core.NodeViewHolder.{CurrentView, GetCurrentView}
import scorex.core.api.http.ApiRoute
import scorex.core.settings.Settings
import scorex.core.transaction.box.proposition.PublicKey25519Proposition

import scala.concurrent.Future
import scala.concurrent.duration._

@Path("/wallet")
@Api(value = "/wallet")
class WalletApiRoute(val settings: Settings, nodeViewHolder: ActorRef)(implicit val context: ActorRefFactory) extends ApiRoute {

  import context.dispatcher

  implicit val askTimeout = Timeout(15.seconds)

  override lazy val route: Route = pathPrefix("wallet") {
    payment ~ address ~ funds ~ coinage
  }

  @Path("/payment")
  @ApiOperation(value = "Make payment", httpMethod = "GET")
  @Produces(Array("text/plain"))
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "address", required = true, dataType = "string", paramType = "query", value = "XxYyZz"),
    new ApiImplicitParam(name = "amount", required = true, dataType = "integer", paramType = "query", value = "1000"),
    new ApiImplicitParam(name = "fee", required = true, dataType = "integer", paramType = "query", value = "10")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "OK")
  ))
  def payment: Route = get {
    path("payment") {
      parameter("address", "amount".as[Long], "fee".as[Int]) { (address, amount, fee) =>
        complete {
          val recipient = PublicKey25519Proposition(ByteKey.base58(address).array)
          getWalletForTx.map {
            case WalletForTransaction(Some(wallet), height) =>
              val maybePayment = wallet.createPayment(recipient, amount, fee, height)
              nodeViewHolder ! ReturnWallet(maybePayment)
              maybePayment.map(200 -> _.id.base58).getOrElse(400 -> "Insufficient funds")

            case WalletForTransaction(None, _) =>
              400 -> "Wallet locked"
          }
        }
      }
    }
  }

  @Path("/address")
  @ApiOperation(value = "Get this node's address", httpMethod = "GET")
  @Produces(Array("text/plain"))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "base58 encoded address")
  ))
  def address: Route = get {
    path("address") {
      complete {
        getWallet.map(_.secret.publicKeyBytes.base58)
      }
    }
  }

  @Path("/funds")
  @ApiOperation(value = "Get this node's funds", httpMethod = "GET")
  @Produces(Array("text/plain"))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "0")
  ))
  def funds: Route = get {
    path("funds") {
      complete(getWallet.map(_.balance.toString))
    }
  }

  @Path("/coinage")
  @ApiOperation(value = "Get this node's accumulated coin-age", httpMethod = "GET")
  @Produces(Array("text/plain"))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "0")
  ))
  def coinage: Route = get {
    path("coinage") {
      complete(getView.map(v => v.vault.accumulatedCoinAge(v.history.height).toString))
    }
  }

  def getView: Future[CurrentView[ElmBlocktree, ElmMinState, ElmWallet, ElmMemPool]] =
    nodeViewHolder.ask(GetCurrentView).mapTo[CurrentView[ElmBlocktree, ElmMinState, ElmWallet, ElmMemPool]]

  def getWallet: Future[ElmWallet] =
    getView.map(_.vault)

  def getWalletForTx: Future[WalletForTransaction] =
    nodeViewHolder.ask(GetWalletForTransaction).mapTo[WalletForTransaction]
}
