package io.scalac.elm.api

import javax.ws.rs.{Path, Produces, QueryParam}

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import io.scalac.elm.history.ElmBlocktree
import io.scalac.elm.state.{ElmMemPool, ElmMinState, ElmWallet}
import io.scalac.elm.transaction.ElmTransaction
import io.swagger.annotations._
import scorex.core.LocalInterface.LocallyGeneratedTransaction
import scorex.core.NodeViewHolder
import scorex.core.NodeViewHolder.CurrentView
import scorex.core.api.http.ApiRoute
import scorex.core.settings.Settings
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.crypto.encode.Base58

import scala.concurrent.Future

@Path("/wallet")
@Api(value = "/wallet")
class WalletApiRoute(val settings: Settings, nodeViewHolder: ActorRef)(implicit val context: ActorRefFactory) extends ApiRoute {

  import context.dispatcher

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
      parameter("address", "amount".as[Long], "fee".as[Int]) { (address, amount, priority) =>
        complete {
          val recipient = PublicKey25519Proposition(Base58.decode(address).get)
          getView.map { case CurrentView(blocktree, _, wallet, _) =>
            wallet.createPayment(recipient, amount, priority, blocktree.height) match {
              case Some(payment) =>
                nodeViewHolder ! LocallyGeneratedTransaction[PublicKey25519Proposition, ElmTransaction](payment)
                Base58.encode(payment.id)
              case None =>
                "Insufficient funds"
            }
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
        getWallet.map(w => Base58.encode(w.secret.publicKeyBytes))
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
      complete(getWallet.map(_.currentBalance.toString))
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
    nodeViewHolder.ask(NodeViewHolder.GetCurrentView).mapTo[CurrentView[ElmBlocktree, ElmMinState, ElmWallet, ElmMemPool]]

  def getWallet: Future[ElmWallet] =
    getView.map(_.vault)
}
