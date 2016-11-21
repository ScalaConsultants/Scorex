package io.scalac.elm.transaction


import java.nio.charset.StandardCharsets
import java.util.UUID

import io.circe._
import io.circe.generic.auto._
import io.scalac.elm.serialization.JsonSerialization
import scorex.core.NodeViewModifierCompanion
import scorex.core.crypto.hash.FastCryptographicHash
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.proposition.PublicKey25519Proposition


object ElmTransaction extends NodeViewModifierCompanion[ElmTransaction] with JsonSerialization[ElmTransaction] {
  val codec = getCodec

  def newTxId: Array[Byte] = FastCryptographicHash.hash(UUID.randomUUID().toString.getBytes(StandardCharsets.US_ASCII))
}

case class ElmTransaction(
  inputs: List[TxInput],
  outputs: List[TxOutput],
  fee: Long,
  id: Array[Byte] = ElmTransaction.newTxId,
  timestamp: Long = System.currentTimeMillis()
) extends Transaction[PublicKey25519Proposition] {

  override type M = ElmTransaction

  override lazy val messageToSign = ElmTransaction.bytes(this)

  override def companion: NodeViewModifierCompanion[ElmTransaction] = ElmTransaction
  override def json: Json = ElmTransaction.toJson(this)
}

