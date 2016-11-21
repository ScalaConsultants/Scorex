package io.scalac.elm.history

import io.circe.generic.auto._
import io.scalac.elm.serialization.JsonSerialization
import io.scalac.elm.util.ByteKey
import scorex.core.NodeViewModifier.{ModifierId, ModifierTypeId}
import scorex.core.consensus.SyncInfo
import scorex.core.network.message.SyncInfoSpec

/**
  * We sync by sharing all the block IDs within a blocktree.
  * We cannot calculate starting points without the knowing anything about the other tree (or only its leaves). In the
  * extreme case (that always happens, at the begining) two blocktrees have completely different set of leaves.
  * That's why we need to send all block IDs, which may become highly inefficient as the tree grows.
  * Can we do any better?
  */
case class ElmSyncInfo(answer: Boolean, leaves: Set[ByteKey], blocks: Set[ByteKey]) extends SyncInfo {
  override def bytes: Array[Byte] = ElmSyncInfo.bytes(this)

  @deprecated("unnecessary", "")
  override def startingPoints: Seq[(ModifierTypeId, ModifierId)] = Nil
}

object ElmSyncInfo extends JsonSerialization[ElmSyncInfo] {
  val codec = getCodec
}

object ElmSyncInfoSpec extends SyncInfoSpec[ElmSyncInfo](ElmSyncInfo.parse)
