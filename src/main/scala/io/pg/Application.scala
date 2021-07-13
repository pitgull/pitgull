package io.pg

import cats.data.NonEmptyList
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.effect.implicits._
import cats.syntax.all._
import io.odin.Logger
import io.pg.background.BackgroundProcess
import io.pg.config.ProjectConfigReader
import io.pg.gitlab.Gitlab
import io.pg.gitlab.webhook.WebhookEvent
import io.pg.messaging._
import io.pg.webhook._
import org.http4s.HttpApp
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.implicits._
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.SttpBackend
import sttp.client3.http4s.Http4sBackend

import scala.concurrent.ExecutionContext
import io.github.vigoo.prox.ProxFS2

sealed trait Event extends Product with Serializable

object Event {
  final case class Webhook(value: WebhookEvent) extends Event
}

final class Application[F[_]](
  val routes: HttpApp[F],
  val background: NonEmptyList[BackgroundProcess[F]]
)

object Application {

  def resource[F[_]: Logger: Async](
    config: AppConfig
  ): Resource[F, Application[F]] = {
    implicit val proxfs2: ProxFS2[F] = ProxFS2[F]

    ProjectConfigReader
      .nixJsonConfig[F]
      .toResource
      .flatMap { implicit pcr =>
        Queue
          .bounded[F, Event](config.queues.maxSize)
          .map(Channel.fromQueue(_))
          .toResource
          .flatMap { eventChannel =>
            implicit val webhookChannel: Channel[F, WebhookEvent] =
              eventChannel.only[Event.Webhook].imap(_.value)(Event.Webhook)

            BlazeClientBuilder[F](ExecutionContext.global)
              .resource
              .map(
                org
                  .http4s
                  .client
                  .middleware
                  .Logger(logHeaders = true, logBody = false, redactHeadersWhen = config.middleware.sensitiveHeaders.contains)
              )
              .map { client =>
                implicit val backend: SttpBackend[F, Fs2Streams[F]] =
                  Http4sBackend.usingClient[F](client)

                implicit val gitlab: Gitlab[F] =
                  Gitlab.sttpInstance[F](config.git.apiUrl, config.git.apiToken)

                implicit val projectActions: ProjectActions[F] =
                  ProjectActions.instance[F]

                implicit val stateResolver: StateResolver[F] =
                  StateResolver.instance[F]

                implicit val mergeRequests: MergeRequests[F] =
                  MergeRequests.instance[F]

                val webhookProcess = BackgroundProcess.fromProcessor(
                  webhookChannel
                )(Processor.simple(WebhookProcessor.instance[F]))

                new Application[F](
                  routes = WebhookRouter.routes[F].orNotFound,
                  background = NonEmptyList.one(webhookProcess)
                )
              }
          }
      }
  }

}
