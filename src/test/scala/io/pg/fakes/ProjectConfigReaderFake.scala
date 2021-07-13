package io.pg.fakes

import cats.MonadThrow
import cats.effect.Ref
import cats.implicits._
import cats.mtl.Stateful
import io.pg.MergeRequestState
import io.pg.ProjectActions
import io.pg.config.Matcher
import io.pg.config.ProjectConfigReader
import io.pg.gitlab.webhook.Project
import monocle.macros.Lenses

trait FakeState

object ProjectConfigReaderFake {

  @Lenses
  sealed case class State(
    configs: Map[Long, Matcher]
  )

  object State {
    val initial: State = State(Map.empty)

    /** A collection of modifiers on the state, which will be provided together with the instance using it.
      */
    trait Modifiers[F[_]] {
      def register(projectId: Long, config: Matcher): F[Unit]
    }

  }

  type Data[F[_]] = Stateful[F, State]
  def Data[F[_]: Data]: Data[F] = implicitly[Data[F]]

  def refInstance[F[_]: Ref.Make: MonadThrow]: F[ProjectConfigReader[F] with State.Modifiers[F]] =
    Ref[F].of(State(Map.empty)).map(FakeUtils.statefulRef(_)).map(implicit F => instance[F])

  def instance[F[_]: Data: MonadThrow]: ProjectConfigReader[F] with State.Modifiers[F] =
    new ProjectConfigReader[F] with State.Modifiers[F] {

      def readConfig(project: Project): MergeRequestState => F[ProjectActions.Matched[Unit]] = s =>
        Data[F]
          .get
          .flatMap(_.configs.get(project.id).liftTo[F](new Throwable(s"Unknown project: $project")))
          .map(matcher => ProjectActions.compileMatcher(matcher).matches(s))

      def register(projectId: Long, config: Matcher): F[Unit] =
        Data[F].modify(State.configs.modify(_ + (projectId -> config)))

    }

}
