package io.scalac.elm.network

import io.scalac.elm.ElmApp
import io.scalac.elm.config.SimConfig
import io.scalac.elm.network.Deserializer._
import io.scalac.elm.network.NetworkNode._
import io.scalac.elm.transaction.ElmBlock
import org.slf4j.LoggerFactory

import scalaj.http.{Http, HttpOptions}

object NetworkNode {
  class UnexpectedHttpStatus(code: Int) extends Exception(s"Unexpected status code: $code")

  val unexpected: Int => Nothing = code => throw new UnexpectedHttpStatus(code)
  val throwError: Throwable => Nothing = throw _
}

case class NetworkNode(app: ElmApp, config: SimConfig) {

  val name: String = app.nodeName
  val port: Int = app.settings.rpcPort
  lazy val publicKey: String = walletAddress()

  private val log = LoggerFactory.getLogger(s"${getClass.getName}.$name")
  private val queryTimeout = config.http.timeout.toMillis.toInt

  def queryApi[Result](desc: String)(relUri: String, params: Map[String, String] = Map.empty)
    (parse: Parse[Result], statusHandler: Int => Result = unexpected)
    (onError: Throwable => Result = throwError) : Result = {

    val uri = s"http://localhost:$port/$relUri"
    log.debug(s"Trying to $desc: $uri")

    try {
      val result = Http(uri)
        .header("Accept", "application/json, text/plain")
        .option(HttpOptions.readTimeout(queryTimeout))
        .params(params)
        .asString

      if (result.code == 200) {
        log.debug(s"Received $result from $uri")
        parse(result.body)
      } else statusHandler(result.code)
    } catch {
      case ex: Exception => onError(ex)
    }
  }

  def walletAddress(): String =
    queryApi("query wallet address")("wallet/address")(asString)()

  def walletFunds(): Long =
    queryApi("query wallet funds")("wallet/funds")(asLong)(_ => 0)

  def makePayment(recipientAddress: String, amount: Long, fee: Long): Option[String] =
    queryApi("make a payment")("wallet/payment", params = Map(
      "address" -> recipientAddress,
      "amount"-> amount.toString,
      "fee" -> fee.toString
    ))(id => Option(asString(id)), {
      case 400 => None
      case o   => unexpected(o)
    })(_ => None)

  def mainchain(): List[ElmBlock] =
    queryApi("query mainchain")("blocktree/blocks/chain/1")(asBlockList)(_ => Nil)

  def mainchainIds(): List[String] =
    queryApi("query mainchain IDs")("blocktree/mainchain")(asStringList)(_ => Nil)

  def block(blockId: String): ElmBlock =
    queryApi("query block")(s"blocktree/block/$blockId")(asBlock)()

  def leaves(): List[String] =
    queryApi("query blocktree leaves")("blocktree/leaves")(asStringList)(_ => Nil)
}
