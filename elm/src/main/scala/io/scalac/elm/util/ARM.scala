package io.scalac.elm.util

import scala.util.Try
import scala.util.control.Exception._

object ARM {
  def using[T, U](resource: T)(block: T => U)(onError: PartialFunction[Throwable, U] = nothingCatcher)(ultimately: T => Unit): U = {
    catching(onError).andFinally(Try(ultimately(resource)))(block(resource))
  }
}
