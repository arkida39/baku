import ReleaseTransformations.*
import sbtversionpolicy.withsbtrelease.ReleaseVersion

ThisBuild / scalaVersion := "3.4.3"
ThisBuild / scalacOptions := Seq("-deprecation", "-release:11", "-no-indent")

def extractVersion(version: String): (String, String) = {
  val VersionPattern = """^(\d+\.\d+\.\d+)\.(\d+).*""".r
  version match {
    case VersionPattern(tapirVersion, bakuVersion) => (tapirVersion, bakuVersion)
    case _                                         =>
      throw new MessageOnlyException(
          s"Version '${version}' is invalid. Expected format: (Major.Minor.Patch.Build)"
      )
  }
}

val tapirVersion = Def.setting {
  extractVersion(version.value)._1
}

lazy val baku = (project in file("."))
  .settings(
      organization := "io.github.arkida39",
      licenses := Seq(License.Apache2),
      libraryDependencies ++= Seq(
          "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion.value
      ),
      releaseVersion := { ver =>
        ver.stripSuffix("-SNAPSHOT")
      },
      releaseNextVersion := { ver =>
        val (baseVersion, bakuVersion) = extractVersion(ver)
        s"$baseVersion.${bakuVersion.toInt + 1}-SNAPSHOT"
      },
      releaseProcess := Seq[ReleaseStep](
          checkSnapshotDependencies,
          inquireVersions,
          runClean,
          // runTest,
          setReleaseVersion,
          commitReleaseVersion,
          tagRelease,
          setNextVersion,
          commitNextVersion
      )
  )
