package io.scalac.elm.functional

import cats.data.Xor
import io.circe.jawn
import io.scalac.elm.transaction.ElmBlock

object ApiResponseDeserialization {

  def toAddress(body: String): String = body

  def toFunds(body: String): Int = body.toInt

  def toPayment(body: String): Unit = ()

  def toBlockAddresses(body: String): Seq[String] = body.split('\n').map(_.trim)

  def toBlock(body: String): ElmBlock = jawn.parse(body) match {
    case Xor.Left(error) => throw new IllegalStateException(error)
    case Xor.Right(json) => ElmBlock.fromJson(json).getOrElse(throw new IllegalStateException("Invalid response"))
  }
}
