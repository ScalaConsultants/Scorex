package io.scalac.elm.history

import io.circe.generic.auto._
import io.scalac.elm.serialization.JsonSerialization
import io.scalac.elm.transaction.ElmBlock
import scorex.core.NodeViewModifier.{ModifierId, ModifierTypeId}
import scorex.core.consensus.SyncInfo
import scorex.core.network.message.SyncInfoSpec

case class ElmSyncInfo(answer: Boolean, leavesIds: Seq[ModifierId]) extends SyncInfo {
  override def bytes: Array[Byte] = ElmSyncInfo.bytes(this)

  override def startingPoints: Seq[(ModifierTypeId, ModifierId)] =
    leavesIds.map(ElmBlock.ModifierTypeId -> _)
}

object ElmSyncInfo extends JsonSerialization[ElmSyncInfo] {
  val codec = getCodec
}

object ElmSyncInfoSpec extends SyncInfoSpec[ElmSyncInfo](ElmSyncInfo.parse)
