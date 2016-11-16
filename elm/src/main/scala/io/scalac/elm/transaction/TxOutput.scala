package io.scalac.elm.transaction

import java.nio.charset.StandardCharsets
import java.util.UUID

import io.circe.generic.auto._
import io.scalac.elm.serialization.JsonSerialization
import scorex.core.transaction.box.Box
import scorex.core.transaction.box.proposition.PublicKey25519Proposition

object TxOutput extends JsonSerialization[TxOutput] {
  val codec = getCodec
}

case class TxOutput(
  value: Long,
  proposition: PublicKey25519Proposition,
  id: Array[Byte] = UUID.randomUUID().toString.getBytes(StandardCharsets.US_ASCII),
  depth: Option[Int] = None
) extends Box[PublicKey25519Proposition] {

  def bytes: Array[Byte] = TxOutput.bytes(this)
}
