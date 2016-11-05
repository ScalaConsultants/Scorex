package io.scalac.elm.consensus

import io.scalac.elm.serialization.ByteSerialization
import io.scalac.elm.transaction.ElmBlock
import scorex.core.NodeViewModifier.{ModifierId, ModifierTypeId}
import scorex.core.consensus.BlockChain.Score
import scorex.core.consensus.SyncInfo
import scorex.core.network.message.SyncInfoSpec

//FIXME: Not sure whether to use block IDs or score
case class ElmSyncInfo(answer: Boolean, lastBlockId: ModifierId, score: Score) extends SyncInfo {
  override def bytes: Array[Byte] = ElmSyncInfo.bytes(this)

  override def startingPoints: Seq[(ModifierTypeId, ModifierId)] =
    Seq(ElmBlock.ModifierTypeId -> lastBlockId)
}

object ElmSyncInfo extends ByteSerialization[ElmSyncInfo]

object ElmSyncInfoSpec extends SyncInfoSpec[ElmSyncInfo](ElmSyncInfo.parse)
