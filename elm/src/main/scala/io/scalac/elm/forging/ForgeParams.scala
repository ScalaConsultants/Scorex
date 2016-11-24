package io.scalac.elm.forging

import io.scalac.elm.transaction.ElmTransaction

case class ForgeParams(coinAge: Long, transactions: Seq[ElmTransaction])
