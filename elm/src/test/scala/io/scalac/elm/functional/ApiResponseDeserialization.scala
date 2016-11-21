package io.scalac.elm.functional

import cats.data.Xor
import io.circe.jawn
import io.scalac.elm.transaction.ElmBlock

object ApiResponseDeserialization {

  def toAddress(body: String): String = body

  def toFunds(body: String): Long = body.toLong

  def toPayment(body: String): String = body

  def toBlockAddresses(body: String): Seq[String] = jawn.parse(body) match {
    case Xor.Left(error) => throw new IllegalStateException(s"Invalid body: $body", error)
    case Xor.Right(json) => json.asArray.toList.flatten.flatMap(_.asString).toSeq
  }

  def toBlock(body: String): ElmBlock = jawn.parse(body) match {
    case Xor.Left(error) => throw new IllegalStateException(s"Invalid body: $body", error)
    case Xor.Right(json) => ElmBlock.fromJson(json).getOrElse(throw new IllegalStateException("Invalid response"))
  }
}
