import Dependencies.*
import complete.DefaultParsers._

// explicit import to avoid clash with gatling plugin

import java.net.URL
import scala.concurrent.duration.DurationInt
import scala.sys.process.Process

val scala2_12 = "2.12.17"
val scala2_13 = "2.13.10"
val scala3 = "3.2.2"

lazy val `ztapir-aws-lambda-test` = project
  .in(file("."))
  .settings(
    libraryDependencies ++= zio ++ `zio-logging` ++ circe ++ tapir ++ (scalatest).map(_ % Test),
    scalacOptions              := Seq("-Ykind-projector"),
    assembly / assemblyJarName := "ztapir-aws-lambda-tests.jar",
    assembly / test            := {}, // no tests before building jar
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "io.netty.versions.properties")                    => MergeStrategy.first
      case PathList(ps @ _*) if ps.last contains "FlowAdapters"                    => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "module-info.class"                     => MergeStrategy.first
      case _ @("scala/annotation/nowarn.class" | "scala/annotation/nowarn$.class") => MergeStrategy.first
      case PathList("deriving.conf")                                               => MergeStrategy.concat // FIXME get rid of zio.json
      case x => (assembly / assemblyMergeStrategy).value(x)
    },
    Test / test := {
      (Test / test)
        .dependsOn(
          Def.sequential(
            (Compile / runMain).toTask(" sttp.tapir.serverless.aws.lambda.zio.tests.LambdaSamTemplate"),
            assembly
          )
        )
        .value

    },
    Test / testOptions ++= {
      val log = sLog.value
      // process uses template.yaml which is generated by `LambdaSamTemplate` called above
//      lazy val sam = Process("sam local start-api --warm-containers EAGER").run()
//      Seq(
//        Tests.Setup(() => {
//          val samReady = PollingUtils.poll(60.seconds, 1.second) {
//            sam.isAlive() && PollingUtils.urlConnectionAvailable(new URL(s"http://127.0.0.1:3000/health"))
//          }
//          if (!samReady) {
//            sam.destroy()
//            val exit = sam.exitValue()
//            log.error(s"failed to start sam local within 60 seconds (exit code: $exit")
//          }
//        }),
//        Tests.Cleanup(() => {
//          sam.destroy()
//          val exit = sam.exitValue()
//          log.info(s"stopped sam local (exit code: $exit")
//        })
//      )
//      lazy val sam = Process("sam local start-api --warm-containers EAGER").run()
      Seq(
        Tests.Setup(() => {
          val samReady = PollingUtils.poll(60.seconds, 1.second) {
            PollingUtils.urlConnectionAvailable(new URL(s"http://127.0.0.1:3000/health"))
          }
          if (!samReady) {
//            sam.destroy()
//            val exit = sam.exitValue()
            log.error(s"failed to start sam local within 60 seconds (exit code: exit")
          }
        }),
        Tests.Cleanup(() => {
//          sam.destroy()
//          val exit = sam.exitValue()
          log.info(s"stopped sam local (exit code: exit")
        })
      )
    },
    Test / parallelExecution := false
  )
  .dependsOn(
    modules.`ztapir-aws-lambda`
  )
