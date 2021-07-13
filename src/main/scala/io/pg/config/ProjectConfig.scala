package io.pg.config

import cats.Applicative
import cats.effect.ExitCode
import cats.effect.Sync
import cats.effect.kernel.Concurrent
import cats.syntax.all._
import cats.tagless.finalAlg
import fs2.io.file.Files
import io.github.vigoo.prox.ProxFS2
import io.pg.MergeRequestState
import io.pg.MergeRequestState.Status._
import io.pg.ProjectActions
import io.pg.config.Mismatch.Author
import io.pg.config.Mismatch.Description
import io.pg.config.Mismatch.NoneMatched
import io.pg.gitlab.webhook.Project
import io.pg.nix.Nix
import org.http4s.Uri
import org.http4s.syntax.literals._

import java.nio.file.Paths
import scala.util.chaining._

@finalAlg
trait ProjectConfigReader[F[_]] {
  //ideally this will have F outside, or something
  //todo rename
  def readConfig(project: Project): MergeRequestState => F[ProjectActions.Matched[Unit]]
}

object ProjectConfigReader {

  def test[F[_]: Applicative]: ProjectConfigReader[F] =
    new ProjectConfigReader[F] {

      def semver(level: String) = Matcher.Description(TextMatcher.Matches(s"(?s).*labels:.*semver-$level.*"))

      val anyLibraryPatch = semver("patch")

      val fromWMS = Matcher.Description(TextMatcher.Matches("""(?s).*((com\.ocado\.ospnow\.wms)|(com\.ocado\.gm\.wms))(?s).*"""))

      val config = Matcher.Many(
        List(
          Matcher.Author(TextMatcher.Matches("(scala_steward)|(michal.pawlik)|(j.kozlowski)")),
          Matcher.PipelineStatus("SUCCESS"),
          (
            semver("minor").and(fromWMS)
          ).or(semver("patch"))
        )
      )

      def readConfig(project: Project): MergeRequestState => F[ProjectActions.Matched[Unit]] =
        s => ProjectActions.compileMatcher(config).matches(s).pure[F]

    }

  def nixJsonConfig[F[_]: Concurrent: ProxFS2: Files]: F[ProjectConfigReader[F]] = {
    val prox: ProxFS2[F] = implicitly[ProxFS2[F]]
    import prox._

    val inputState = """{ status = "success"; author = "user1@gmail.com"; description = "hello werld"; }"""

    import Nix.syntax._

    implicit val statusToNix: Nix.From[MergeRequestState.Status] = Nix.From[String].contramap {
      case Success      => "success"
      case Other(value) => value
    }

    implicit val stateAsNix: Nix.From[MergeRequestState] = mrs =>
      Nix.Record(
        mrs.status.toNix.at("status") ++
          mrs.authorUsername.toNix.at("author") ++
          mrs.description.getOrElse("").toNix.at("description")
      )

    implicit val runner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

    // returns sha256 of prefetched file
    def prefetch(url: Uri): F[String] = Process("nix-prefetch-url", List(url.renderString))
      .toFoldMonoid(fs2.text.utf8Decode[F])
      .drainError(_.drain)
      .run()
      .pipe(checkExitCode)
      .map(_.output.trim)

    def args(state: MergeRequestState): F[List[String]] = {
      val url = uri"http://localhost:8081/wms.nix"
      import Nix.syntax._

      prefetch(url).map { sha256 =>
        val theArg =
          Nix
            .builtins
            .fetchurl
            .applied(
              Nix.obj(
                "url" := url.renderString,
                //todo: prefetch-url to determine this sha
                "sha256" := sha256
              )
            )
            .imported
            .applied(state.toNix)

        List("eval", theArg.render, "--json")
      }
    }

    def checkExitCode[O, E]: F[ProcessResult[O, E]] => F[ProcessResult[O, E]] =
      _.ensure(new Throwable("Invalid exit code"))(
        _.exitCode == ExitCode.Success
      )

    val instance: ProjectConfigReader[F] = new ProjectConfigReader[F] {
      import Result._

      //todo: remove these and the existing scala DSL, use the in-nix model everywhere
      def convertTextMismatch(actual: String): TextRule => ProjectActions.Mismatch = {
        case TextRule.Equal(v)   => ProjectActions.Mismatch.ValueMismatch(v, actual)
        case TextRule.Matches(r) => ProjectActions.Mismatch.RegexMismatch(r, actual)
      }

      def convertMismatch(m: Mismatch): ProjectActions.Mismatch = m match {
        case Mismatch.Status(expected, actual) => ProjectActions.Mismatch.ValueMismatch(expected.value, actual.value).atPath("status")
        case Author(expected, actual)          => convertTextMismatch(actual)(expected).atPath("author")
        case Description(expected, actual)     => convertTextMismatch(actual)(expected).atPath("description")
        case NoneMatched(expected)             =>
          //todo: unnecessary wrapping in List in this model
          ProjectActions.Mismatch.ManyFailed(List(expected.map(convertMismatch)))
      }

      val convert: Result => ProjectActions.Matched[Unit] = {
        case Ok                => Right(())
        case NotOk(mismatches) => Left(mismatches.map(convertMismatch))
      }

      def readConfig(project: Project): MergeRequestState => F[ProjectActions.Matched[Unit]] = state =>
        args(state)
          .flatMap { argz =>
            Process("nix", argz)
              .toFoldMonoid(fs2.text.utf8Decode[F])
              .fromStream(Files[F].readAll(Paths.get("./wms.nix"), 4096), flushChunks = false)
              .run()
          }
          .pipe(checkExitCode)
          .map(_.output)
          .flatTap(out => Sync[F].delay(println(out)))
          .flatMap(io.circe.parser.decode[Result](_).liftTo[F])
          .map(convert)

    }

    val ensureCommandExists =
      Process("bash", "-c" :: s"command -v nix" :: Nil)
        .drainOutput(_.drain)
        .run()
        .pipe(checkExitCode)
        .adaptError { case e =>
          new Throwable(s"Command nix not found", e)
        }

    ensureCommandExists.as(instance)
  }

}
