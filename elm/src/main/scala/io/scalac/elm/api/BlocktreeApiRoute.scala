package io.scalac.elm.api

import javax.ws.rs.Path

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.server._
import akka.pattern.ask
import akka.util.Timeout
import io.circe._
import io.circe.syntax._
import io.scalac.elm.history.ElmBlocktree
import io.scalac.elm.util.ByteKey
import io.swagger.annotations._
import scorex.core.NodeViewHolder
import scorex.core.NodeViewHolder.CurrentView
import scorex.core.api.http.ApiRoute
import scorex.core.settings.Settings
import scala.concurrent.duration._

@Path("/blocktree")
@Api(value = "/blocktree")
class BlocktreeApiRoute(val settings: Settings, nodeViewHolder: ActorRef)(implicit val context: ActorRefFactory) extends ApiRoute {

  import context.dispatcher

  implicit val askTimeout = Timeout(15.seconds)

  implicit val jsonMarshaller: ToEntityMarshaller[Json] =
    Marshaller.StringMarshaller.wrap(MediaTypes.`application/json`)(_.spaces4)

  override lazy val route: Route = pathPrefix("blocktree") {
    mainchain ~ leaves ~ block ~ chain ~ blocksOfChain
  }

  @Path("/mainchain")
  @ApiOperation(value = "get main-chain blocks' IDs", httpMethod = "GET")
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "list of block IDs ordered as in the main-chain")
  ))
  def mainchain: Route = get {
    path("mainchain") {
      complete {
        getBlocktree.map(_.mainChain.toList.map(_.id.base58).asJson)
      }
    }
  }

  @Path("/leaves")
  @ApiOperation(value = "get leaves", httpMethod = "GET")
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "list of blocktree leaves")
  ))
  def leaves: Route = get {
    path("leaves") {
      complete {
        getBlocktree.map(_.sortedLeaves.map(_.base58).asJson)
      }
    }
  }

  @Path("/block/{id}")
  @ApiOperation(value = "get block by ID", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "id", required = true, dataType = "string", paramType = "path", value = "XxYyZz")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "JSON representation of a block"),
    new ApiResponse(code = 404, message = "block not found")
  ))
  def block: Route  = get {
    path("block" / Segment) { id =>
      rejectEmptyResponse {
        complete {
          getBlocktree.map(_.blockById(ByteKey.base58(id).array).map(_.json))
        }
      }
    }
  }

  @Path("/chain/{n}")
  @ApiOperation(value = "get chain by score, IDs only", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "n", required = true, dataType = "integer", paramType = "path", value = "1")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "list of block IDs ordered as in the blockchain"),
    new ApiResponse(code = 404, message = "chain not found")
  ))
  def chain: Route  = get {
    path("chain" / IntNumber) { n =>
      rejectEmptyResponse {
        complete {
          getBlocktree.map(_.chainOf(n).map(_.toList).map(_.map(_.id.base58).asJson))
        }
      }
    }
  }

  @Path("/blocks/chain/{n}")
  @ApiOperation(value = "get chain by score", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "n", required = true, dataType = "integer", paramType = "path", value = "1")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "list of blocks ordered as in the blockchain"),
    new ApiResponse(code = 404, message = "chain not found")
  ))
  def blocksOfChain: Route  = get {
    path("blocks" / "chain" / IntNumber) { n =>
      rejectEmptyResponse {
        complete {
          getBlocktree.map(_.chainOf(n).map(_.toList).map(_.map(_.block.json).asJson))
        }
      }
    }
  }

  private def getBlocktree =
    nodeViewHolder.ask(NodeViewHolder.GetCurrentView).mapTo[CurrentView[ElmBlocktree, _, _, _]].map(_.history)
}
