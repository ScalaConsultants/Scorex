package io.scalac.elm.config

import scorex.core.app.ApplicationVersion


//FIXME: take values from build file or config
case class AppInfo(name: String = "elm", version: String = "1.0.0") {

  val appVersion = {
    //FIXME: catch exception, inform of the proper version format
    val major :: minor :: rev :: Nil = version.split("\\.").toList.map(_.toInt)
    ApplicationVersion(major, minor, rev)
  }
}
