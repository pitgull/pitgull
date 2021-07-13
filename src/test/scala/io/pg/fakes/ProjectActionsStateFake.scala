package io.pg.fakes

import cats.Monad
import cats.data.Chain
import cats.effect.Ref
import cats.implicits._
import cats.mtl.Stateful
import io.odin.Logger
import io.pg.MergeRequestState
import io.pg.MergeRequestState.Mergeability
import io.pg.ProjectAction
import io.pg.ProjectAction.Merge
import io.pg.ProjectAction.Rebase
import io.pg.ProjectActions
import io.pg.StateResolver
import io.pg.gitlab.Gitlab.MergeRequestInfo
import io.pg.gitlab.webhook.Project
import io.scalaland.chimney.dsl._
import monocle.macros.Lenses

object ProjectActionsStateFake {
  sealed case class MergeRequestDescription(projectId: Long, mergeRequestIid: Long)

  object MergeRequestDescription {
    val fromMergeAction: ProjectAction.Merge => MergeRequestDescription = _.transformInto[MergeRequestDescription]
  }

  @Lenses
  sealed case class State(
    mergeRequests: Map[MergeRequestDescription, MergeRequestState],
    actionLog: Chain[ProjectAction]
  )

  object State {
    val initial: State = State(Map.empty, Chain.nil)

    /** A collection of modifiers on the state, which will be provided together with the instance using it.
      */
    trait Modifiers[F[_]] {
      // returns Iid of created MR
      def open(projectId: Long, authorUsername: String, description: Option[String]): F[Long]
      def finishPipeline(projectId: Long, mergeRequestIid: Long): F[Unit]
      def setMergeability(projectId: Long, mergeRequestIid: Long, mergeability: Mergeability): F[Unit]
      def getActionLog: F[List[ProjectAction]]
    }

    private[ProjectActionsStateFake] object modifications {

      def logAction(action: ProjectAction): State => State =
        State.actionLog.modify(_.append(action))

      def merge(action: ProjectAction.Merge): State => State =
        State.mergeRequests.modify(_ - MergeRequestDescription.fromMergeAction(action))

      def rebase(action: ProjectAction.Rebase): State => State =
        // Note: this doesn't check for conflicts
        setMergeabilityInternal(action.projectId, action.mergeRequestIid, Mergeability.CanMerge)

      def setMergeabilityInternal(projectId: Long, mergeRequestIid: Long, mergeability: Mergeability): State => State =
        State.mergeRequests.modify { mrs =>
          val key = MergeRequestDescription(projectId, mergeRequestIid)
          mrs ++ mrs.get(key).map(_.copy(mergeability = mergeability)).tupleLeft(key)
        }

      def save(key: MergeRequestDescription, state: MergeRequestState) = State.mergeRequests.modify {
        _ + (key -> state)
      }

      def finishPipeline(key: MergeRequestDescription) = State.mergeRequests.modify {
        _.updatedWith(key) {
          _.map { state =>
            state.copy(status = MergeRequestInfo.Status.Success)
          }
        }
      }

    }

  }

  type Data[F[_]] = Stateful[F, State]
  def Data[F[_]: Data]: Data[F] = implicitly[Data[F]]

  def refInstance[F[_]: Ref.Make: Logger: Monad]: F[ProjectActions[F] with StateResolver[F] with State.Modifiers[F]] =
    Ref[F].of(State.initial).map(FakeUtils.statefulRef(_)).map(implicit F => instance[F])

  /** This instance has both the capabilities of ProjectActions and StateResolver, because they operate on the same state, and the state is
    * sealed by convention.
    */
  def instance[
    F[_]: Data: Monad: Logger
  ]: ProjectActions[F] with StateResolver[F] with State.Modifiers[F] = new ProjectActions[F] with StateResolver[F] with State.Modifiers[F] {

    type Action = ProjectAction
    def resolve(mr: MergeRequestState): F[Option[ProjectAction]] = ProjectActions.defaultResolve[F](mr)

    def execute(action: ProjectAction): F[Unit] = Data[F].modify {
      val actionChange = action match {
        case m: Merge  => State.modifications.merge(m)
        case r: Rebase => State.modifications.rebase(r)
      }

      actionChange >>> State.modifications.logAction(action)
    }

    def resolve(project: Project): F[List[MergeRequestState]] =
      Data[F].get.map(_.mergeRequests).map(_.values.toList)

    def open(projectId: Long, authorUsername: String, description: Option[String]): F[Long] = {

      val getNextId = Data[F]
        .get
        .map(
          _.mergeRequests
            .values
            .filter(_.projectId === projectId)
            .map(_.mergeRequestIid)
            .maxOption
            .getOrElse(0L) + 1L
        )

      getNextId.flatTap { newId =>
        val initState = MergeRequestState(
          projectId = projectId,
          mergeRequestIid = newId,
          authorUsername = authorUsername,
          description = description,
          status = MergeRequestInfo.Status.Other("Created"),
          mergeability = Mergeability.CanMerge
        )

        Data[F].modify {
          State.modifications.save(MergeRequestDescription(projectId, newId), initState)
        }
      }
    }

    def setMergeability(projectId: Long, mergeRequestIid: Long, mergeability: Mergeability): F[Unit] = Data[F].modify {
      State.modifications.setMergeabilityInternal(projectId, mergeRequestIid, mergeability)
    }

    def finishPipeline(projectId: Long, mergeRequestIid: Long): F[Unit] =
      Data[F].modify {
        State.modifications.finishPipeline(MergeRequestDescription(projectId, mergeRequestIid))
      }

    def getActionLog: F[List[ProjectAction]] = Data[F].inspect(_.actionLog.toList)
  }

}
