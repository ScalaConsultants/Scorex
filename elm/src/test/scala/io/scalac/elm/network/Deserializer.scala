package io.scalac.elm.network

import io.circe._
import io.circe.generic.auto._
import io.scalac.elm.core.ElmNodeViewHolder.PaymentResponse
import io.scalac.elm.transaction.ElmBlock

object Deserializer {
  type Parse[R] = String => R

  val asString: Parse[String] =
    identity

  val asLong: Parse[Long] =
    _.toLong

  val asStringList: Parse[List[String]] = s =>
    parser.parse(s).toTry.flatMap(_.as[List[String]].toTry).get

  val asBlock: Parse[ElmBlock] = s =>
    parser.parse(s).toTry.flatMap(ElmBlock.fromJson).get

  val asBlockList: Parse[List[ElmBlock]] = s =>
    parser.parse(s).toTry.map(_.asArray.get.map(ElmBlock.fromJson).map(_.get)).get

  val asPaymentResponse: Parse[PaymentResponse] = s =>
    parser.parse(s).toTry.flatMap(_.as[PaymentResponse].toTry).get
}
