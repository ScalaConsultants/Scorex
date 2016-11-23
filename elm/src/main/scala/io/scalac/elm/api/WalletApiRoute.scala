package io.scalac.elm.api

import javax.ws.rs.{Path, Produces}

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.marshalling.{Marshaller, _}
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import io.circe.Json
import io.circe.syntax._
import io.circe.generic.auto._
import io.scalac.elm.core.ElmNodeViewHolder._
import io.scalac.elm.history.ElmBlocktree
import io.scalac.elm.state.{ElmMemPool, ElmMinState, ElmWallet}
import io.scalac.elm.util.ByteKey
import io.swagger.annotations._
import scorex.core.NodeViewHolder.{CurrentView, GetCurrentView}
import scorex.core.api.http.ApiRoute
import scorex.core.settings.Settings

import scala.concurrent.Future
import scala.concurrent.duration._

@Path("/wallet")
@Api(value = "/wallet")
class WalletApiRoute(val settings: Settings, nodeViewHolder: ActorRef)(implicit val context: ActorRefFactory) extends ApiRoute {

  import context.dispatcher

  implicit val askTimeout = Timeout(15.seconds)

  implicit val jsonMarshaller: ToEntityMarshaller[Json] =
    Marshaller.StringMarshaller.wrap(MediaTypes.`application/json`)(_.spaces4)

  override lazy val route: Route = pathPrefix("wallet") {
    payment ~ relativePayment ~ address ~ funds ~ coinage
  }

  @Path("/payment")
  @ApiOperation(value = "Make payment", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "address", required = true, dataType = "string", paramType = "query", value = "XxYyZz"),
    new ApiImplicitParam(name = "amount", required = true, dataType = "integer", paramType = "query", value = "1000"),
    new ApiImplicitParam(name = "fee", required = true, dataType = "integer", paramType = "query", value = "10")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "non empty string if payment succeeded")
  ))
  def payment: Route = get {
    path("payment") {
      parameter("address", "amount".as[Long], "fee".as[Int]) { (address, amount, fee) =>
        complete {
          nodeViewHolder.ask(PaymentRequest(address, amount, fee))
            .mapTo[PaymentResponse].map(_.asJson)
        }
      }
    }
  }

  @Path("/relative_payment")
  @ApiOperation(value = "Make payment", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "address", required = true, dataType = "string", paramType = "query", value = "XxYyZz"),
    new ApiImplicitParam(name = "ratio", required = true, dataType = "double", paramType = "query", value = "0.5"),
    new ApiImplicitParam(name = "fee", required = true, dataType = "integer", paramType = "query", value = "10"),
    new ApiImplicitParam(name = "min_balance", required = true, dataType = "integer", paramType = "query", value = "500")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "non empty string if payment succeeded")
  ))
  def relativePayment: Route = get {
    path("relative_payment") {
      parameter("address", "ratio".as[Double], "fee".as[Int], "min_balance".as[Long]) { (address, ratio, fee, minBalance) =>
        complete {
          nodeViewHolder.ask(PaymentRequestRelative(address, ratio, fee, minBalance))
            .mapTo[PaymentResponse].map(_.asJson)
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

}
