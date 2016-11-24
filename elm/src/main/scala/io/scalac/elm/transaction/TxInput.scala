package io.scalac.elm.transaction

import io.circe.generic.auto._
import io.scalac.elm.serialization.JsonSerialization
import scorex.core.transaction.box.BoxUnlocker
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.proof.Signature25519

object TxInput extends JsonSerialization[TxInput] {
  val codec = getCodec
}

case class TxInput(closedBoxId: Array[Byte], boxKey: Signature25519)
  extends BoxUnlocker[PublicKey25519Proposition]
