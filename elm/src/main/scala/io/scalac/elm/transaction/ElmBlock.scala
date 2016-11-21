package io.scalac.elm.transaction

import io.circe._
import io.circe.generic.auto._
import io.scalac.elm.serialization.JsonSerialization
import scorex.core.NodeViewModifier.ModifierTypeId
import scorex.core.NodeViewModifierCompanion
import scorex.core.block.Block
import scorex.core.crypto.hash.FastCryptographicHash
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import shapeless.HNil


object ElmBlock extends NodeViewModifierCompanion[ElmBlock] with JsonSerialization[ElmBlock] {
  type GenerationSignature = Array[Byte]

  val ModifierTypeId = 1: Byte

  val codec = getCodec

  val zero: ElmBlock = {
    val zeroSignature = Array.fill(32)(0.toByte)
    val generator = PublicKey25519Proposition(zeroSignature)
    ElmBlock(zeroSignature, 0L, zeroSignature, generator, Nil)
  }
}

case class ElmBlock(
  parentId: Block.BlockId,
  timestamp: Long,
  generationSignature: ElmBlock.GenerationSignature,
  generator: PublicKey25519Proposition,
  txs: Seq[ElmTransaction]
) extends Block[PublicKey25519Proposition, ElmTransaction] {

  import Block._

  override type M = ElmBlock

  override lazy val id: BlockId = FastCryptographicHash(ElmBlock.bytes(this))

  override val modifierTypeId: ModifierTypeId = ElmBlock.ModifierTypeId

  override def companion: NodeViewModifierCompanion[ElmBlock] = ElmBlock

  override def json: Json = ElmBlock.toJson(this)

  def jsonNoTxs: Json = ElmBlock.toJson(this.copy(txs = Nil))

  def updateHeights(height: Int): ElmBlock = {
    val updatedTxs = txs.map { tx =>
      val updatedOuts = tx.outputs.map(_.copy(height = Some(height)))
      tx.copy(outputs = updatedOuts)
    }
    this.copy(txs = updatedTxs)
  }


  @deprecated("why Option?", "")
  override def transactions: Option[Seq[ElmTransaction]] = Some(txs)

  @deprecated("unnecessary", "")
  override type BlockFields = HNil

  @deprecated("unnecessary", "")
  override val version: Version = 0: Byte

  @deprecated("unnecessary", "")
  override lazy val blockFields: BlockFields = HNil

}