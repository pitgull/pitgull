package io.pg

import cats.Applicative
import cats.MonadThrow
import cats.MonoidK
import cats.Show
import cats.data.EitherNel
import cats.data.NonEmptyList
import cats.implicits._
import cats.tagless.autoContravariant
import io.odin.Logger
import io.pg.MergeRequestState.Mergeability.CanMerge
import io.pg.MergeRequestState.Mergeability.HasConflicts
import io.pg.MergeRequestState.Mergeability.NeedsRebase
import io.pg.ProjectAction.Merge
import io.pg.ProjectAction.Rebase
import io.pg.config.Matcher
import io.pg.config.TextMatcher
import io.pg.gitlab.Gitlab

trait ProjectActions[F[_]] {
  type Action
  def resolve(mr: MergeRequestState): F[Option[Action]]
  def execute(action: Action): F[Unit]
}

object ProjectActions {
  def apply[F[_]](implicit F: ProjectActions[F]): F.type = F

  def defaultResolve[F[_]: Applicative: Logger](mr: MergeRequestState): F[Option[ProjectAction]] = mr.mergeability match {
    case CanMerge =>
      ProjectAction
        .Merge(projectId = mr.projectId, mergeRequestIid = mr.mergeRequestIid)
        .some
        .widen[ProjectAction]
        .pure[F]

    case NeedsRebase =>
      ProjectAction
        .Rebase(projectId = mr.projectId, mergeRequestIid = mr.mergeRequestIid)
        .some
        .widen[ProjectAction]
        .pure[F]

    case HasConflicts =>
      Logger[F]
        .info(
          "MR has conflicts, skipping",
          Map("projectId" -> mr.projectId.show, "mergeRequestIid" -> mr.mergeRequestIid.show)
        )
        .as(none)
  }

  def instance[F[_]: Gitlab: Logger: MonadThrow]: ProjectActions[F] = new ProjectActions[F] {

    type Action = ProjectAction

    def resolve(mr: MergeRequestState): F[Option[ProjectAction]] = defaultResolve[F](mr)

    def execute(action: ProjectAction): F[Unit] = {
      val logBefore = Logger[F].info("About to execute action", Map("action" -> action.toString))

      val approve = action match {
        case Merge(projectId, mergeRequestIid) =>
          Logger[F].info("Forcing approval befor merge", Map("action" -> action.toString)) *>
            Gitlab[F].forceApprove(projectId, mergeRequestIid)
        case _                                 =>
          Logger[F].info("Approval forcing not required", Map("action" -> action.toString))
      }

      val perform = action match {
        //todo: perform check is the MR still open?
        //or fall back in case it's not
        //https://www.youtube.com/watch?v=vxKBHX9Datw
        case Merge(projectId, mergeRequestIid) =>
          Gitlab[F].acceptMergeRequest(projectId, mergeRequestIid)

        case Rebase(projectId, mergeRequestIid) =>
          Gitlab[F].rebaseMergeRequest(projectId, mergeRequestIid)
      }

      logBefore *> approve *> perform.handleErrorWith { error =>
        Logger[F]
          .error(
            "Couldn't perform action",
            Map(
              //todo: consier granular fields
              "action" -> action.toString
            ),
            error
          )
      }
    }

  }

  @autoContravariant
  trait MatcherFunction[-In] {
    def matches(in: In): Matched[Unit]
    def atPath(path: String): MatcherFunction[In] = mapFailures(_.map(_.atPath(path)))

    def mapResult(f: Matched[Unit] => Matched[Unit]): MatcherFunction[In] = f.compose(matches).apply(_)
    def mapFailures(f: NonEmptyList[Mismatch] => NonEmptyList[Mismatch]): MatcherFunction[In] = mapResult(_.leftMap(f))
  }

  object MatcherFunction {

    implicit val monoidK: MonoidK[MatcherFunction] = new MonoidK[MatcherFunction] {
      override def combineK[A](x: MatcherFunction[A], y: MatcherFunction[A]): MatcherFunction[A] =
        in => (x.matches(in).toValidated |+| y.matches(in).toValidated).toEither
      override def empty[A]: MatcherFunction[A] = success
    }

    def fromPredicate[In](
      predicate: In => Boolean,
      orElse: In => Mismatch
    ): MatcherFunction[In] =
      _.asRight[Mismatch].ensureOr(orElse)(predicate).toEitherNel.void

    val success: MatcherFunction[Any] =
      _.pure[Matched].void
  }

  sealed trait Mismatch extends Product with Serializable {
    def atPath(path: String): Mismatch = Mismatch.AtPath(path, this)
  }

  object Mismatch {
    final case class AtPath(path: String, mismatch: Mismatch) extends Mismatch
    final case class ValueMismatch(expected: String, actual: String) extends Mismatch
    final case class RegexMismatch(pattern: String, actual: String) extends Mismatch
    final case class ManyFailed(incompleteMatches: List[NonEmptyList[Mismatch]]) extends Mismatch
    case object ValueEmpty extends Mismatch
    case object NegationFailed extends Mismatch

    implicit val show: Show[Mismatch] = Show.fromToString
  }

  type Matched[A] = EitherNel[Mismatch, A]

  def statusMatches(expectedStatus: String): MatcherFunction[MergeRequestState] =
    MatcherFunction
      .fromPredicate[MergeRequestState.Status](
        {
          case MergeRequestState.Status.Success      => expectedStatus.toLowerCase === "success"
          case MergeRequestState.Status.Other(value) => expectedStatus === value
        },
        value => Mismatch.ValueMismatch(expectedStatus, value.toString)
      )
      .atPath(".status")
      .contramap(_.status)

  val matchTextMatcher: TextMatcher => MatcherFunction[String] = {
    case TextMatcher.Equals(expected) =>
      MatcherFunction.fromPredicate(
        _ === expected,
        Mismatch.ValueMismatch(expected, _)
      )
    case TextMatcher.Matches(regex)   =>
      MatcherFunction.fromPredicate(
        regex.r.matches,
        Mismatch.RegexMismatch(regex, _)
      )
  }

  def exists[A](base: MatcherFunction[A]): MatcherFunction[Option[A]] =
    _.fold[Matched[Unit]](Mismatch.ValueEmpty.leftNel)(base.matches)

  def oneOf[A](matchers: List[MatcherFunction[A]]): MatcherFunction[A] = input =>
    matchers
      .traverse(_.matches(input).swap)
      .swap
      .leftMap(Mismatch.ManyFailed)
      .toEitherNel

  def not[A](matcher: MatcherFunction[A]): MatcherFunction[A] = input =>
    matcher.matches(input).swap.leftMap(_ => Mismatch.NegationFailed).void.toEitherNel

  def autorMatches(matcher: TextMatcher): MatcherFunction[MergeRequestState] =
    matchTextMatcher(matcher)
      .atPath(".author")
      .contramap(_.authorUsername)

  def descriptionMatches(matcher: TextMatcher): MatcherFunction[MergeRequestState] =
    exists(matchTextMatcher(matcher))
      .atPath(".description")
      .contramap(_.description)

  val compileMatcher: Matcher => MatcherFunction[MergeRequestState] = {
    case Matcher.Author(email)          => autorMatches(email)
    case Matcher.Description(text)      => descriptionMatches(text)
    case Matcher.PipelineStatus(status) => statusMatches(status)
    case Matcher.Many(values)           => values.foldMapK(compileMatcher)
    case Matcher.OneOf(values)          => oneOf(values.map(compileMatcher))
    case Matcher.Not(underlying)        => not(compileMatcher(underlying))
  }

}

sealed trait ProjectAction extends Product with Serializable

object ProjectAction {
  final case class Merge(projectId: Long, mergeRequestIid: Long) extends ProjectAction
  final case class Rebase(projectId: Long, mergeRequestIid: Long) extends ProjectAction
}
