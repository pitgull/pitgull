package io.pg

import cats.effect.ExitCode
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.Effect
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import cats.effect.Sync
import cats.effect.implicits._
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.syntax.all._
import cats.~>
import io.chrisdavenport.cats.time.instances.all._
import io.odin.Level
import io.odin.Logger
import io.odin.formatter.Formatter
import org.http4s.HttpApp
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.middleware
import cats.effect.Blocker
import io.pg.config.ProjectConfigReader

object Main extends IOApp.Simple {

  def mkLogger[F[_]: Async](fToIO: F ~> IO): Resource[F, Logger[F]] = {

    val console = io.odin.consoleLogger[F](formatter = Formatter.colorful).withMinimalLevel(Level.Info).pure[Resource[F, *]]

    val file = io
      .odin
      .asyncRollingFileLogger[F](
        fileNamePattern = dateTime => show"/tmp/log/pitgull/pitgull-logs-${dateTime.toLocalDate}.txt",
        rolloverInterval = 1.day.some,
        maxFileSizeInBytes = (10L * 1024 * 1024 /* 10MB */ ).some,
        maxBufferSize = 10.some,
        formatter = Formatter.colorful,
        minLevel = Level.Debug
      )

    console |+| file
  }
    .evalTap { logger =>
      Sync[F].delay(
        OdinInterop
          .globalLogger
          .set(
            logger
              .mapK(fToIO)
              .some
          )
      )
    }

  def mkServer[F[_]: Logger: Async](
    config: AppConfig,
    routes: HttpApp[F]
  ) = {
    val app = middleware
      .Logger
      .httpApp(
        logHeaders = true,
        logBody = true,
        logAction = (Logger[F].debug(_: String)).some
      )(routes)

    BlazeServerBuilder[F](ExecutionContext.global)
      .withHttpApp(app)
      .bindHttp(port = config.http.port, host = "0.0.0.0")
      .withBanner(config.meta.banner.linesIterator.toList)
      .resource
  }

  def logStarting[F[_]: Logger](meta: MetaConfig) =
    Logger[F].info("Starting application", Map("version" -> meta.version, "scalaVersion" -> meta.scalaVersion))

  def logStarted[F[_]: Logger](meta: MetaConfig) =
    Logger[F].info("Started application", Map("version" -> meta.version, "scalaVersion" -> meta.scalaVersion))

  def serve[F[_]: Async](fToIO: F ~> IO)(config: AppConfig) =
    for {
      implicit0(logger: Logger[F]) <- mkLogger[F](fToIO)
      _                            <- logStarting(config.meta).toResource
      resources                    <- Application.resource[F](config)
      _                            <- mkServer[F](config, resources.routes)
      _                            <- resources.background.parTraverse_(_.run).background
      _                            <- logStarted(config.meta).toResource
    } yield ()

  def run: IO[Unit] =
    Blocker[IO].use { blocker =>
      ProjectConfigReader.nixJsonConfig[IO](blocker).flatMap(_.readConfig(null)).flatMap(a => IO(println(a)))
    }

}
