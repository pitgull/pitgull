package io.pg

import cats.MonadError
import cats.implicits._
import cats.kernel.Order
import cats.tagless.finalAlg
import io.odin.Logger
import io.pg.gitlab.Gitlab
import io.pg.gitlab.Gitlab.MergeRequestInfo
import io.pg.gitlab.webhook.Project
import io.scalaland.chimney.dsl._
import cats.Show
import monocle.macros.Lenses
import io.pg.nix.Nix
import Nix.syntax._

@finalAlg
trait StateResolver[F[_]] {
  def resolve(project: Project): F[List[MergeRequestState]]
}

object StateResolver {

  def instance[F[_]: Gitlab: Logger: MonadError[*[_], Throwable]](
    implicit SC: fs2.Compiler[F, F]
  ): StateResolver[F] =
    new StateResolver[F] {

      private def findMergeRequests(project: Project): F[List[MergeRequestState]] =
        Gitlab[F]
          .mergeRequests(projectId = project.id)
          .nested
          .map(buildState)
          .value

      private def buildState(
        mr: MergeRequestInfo
      ): MergeRequestState =
        mr
          .into[MergeRequestState]
          .withFieldComputed(
            _.status,
            _.status.getOrElse(MergeRequestInfo.Status.Success).transformInto[MergeRequestState.Status]
          ) //for now - no pipeline means success
          .withFieldComputed(
            _.mergeability,
            info =>
              MergeRequestState
                .Mergeability
                .fromFlags(
                  hasConflicts = info.hasConflicts,
                  needsRebase = info.needsRebase
                )
          )
          .transform

      def resolve(project: Project): F[List[MergeRequestState]] =
        findMergeRequests(project)
          .flatTap { state =>
            Logger[F].info("Resolved MR state", Map("state" -> state.show))
          }

    }

}

//current MR state - rebuilt on every event.
//Checked against rules to come up with a decision.
@Lenses
final case class MergeRequestState(
  projectId: Long,
  mergeRequestIid: Long,
  authorUsername: String,
  description: Option[String],
  status: MergeRequestState.Status,
  mergeability: MergeRequestState.Mergeability
)

object MergeRequestState {
  sealed trait Status extends Product with Serializable

  object Status {
    case object Success extends Status
    final case class Other(value: String) extends Status

    implicit val statusToNix: Nix.From[MergeRequestState.Status] = Nix.From[String].contramap {
      case Success      => "success"
      case Other(value) => value
    }

  }

  sealed trait Mergeability extends Product with Serializable

  object Mergeability {
    case object CanMerge extends Mergeability
    case object NeedsRebase extends Mergeability
    case object HasConflicts extends Mergeability

    def fromFlags(hasConflicts: Boolean, needsRebase: Boolean): Mergeability =
      if (hasConflicts) HasConflicts
      else if (needsRebase) NeedsRebase
      else CanMerge

    implicit val order: Order[Mergeability] = Order.by(List(CanMerge, NeedsRebase, HasConflicts).indexOf)
  }

  implicit val showTrimmed: Show[MergeRequestState] =
    MergeRequestState.description.modify(_.map(TextUtils.trim(maxChars = 80))).apply(_).toString

  implicit val stateAsNix: Nix.From[MergeRequestState] = mrs =>
    Nix.Record(
      mrs.status.toNix.at("status") ++
        mrs.authorUsername.toNix.at("author") ++
        mrs.description.getOrElse("").toNix.at("description")
    )

}
