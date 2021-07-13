package io.pg.config

import cats.data.NonEmptyList
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.ConfiguredJsonCodec

object circe {
  implicit val circeConfig: Configuration =
    Configuration.default.withDiscriminator("kind").withSnakeCaseConstructorNames
}

import circe.circeConfig

@ConfiguredJsonCodec()
sealed trait Result extends Product with Serializable

object Result {
  case object Ok extends Result

  final case class NotOk(mismatches: NonEmptyList[Mismatch]) extends Result
}

@ConfiguredJsonCodec()
sealed trait Mismatch extends Product with Serializable

object Mismatch {
  final case class Status(expected: io.pg.config.Status, actual: io.pg.config.Status) extends Mismatch
  final case class Author(expected: TextRule, actual: String) extends Mismatch
  final case class Description(expected: TextRule, actual: String) extends Mismatch
  final case class NoneMatched(mismatches: NonEmptyList[Mismatch]) extends Mismatch
}

@ConfiguredJsonCodec()
sealed trait TextRule extends Product with Serializable

object TextRule {
  final case class Equal(expected: String) extends TextRule
  final case class Matches(pattern: String) extends TextRule

}

final case class Status(value: String) extends AnyVal

object Status {
  implicit val codec: Codec[Status] = io.circe.generic.extras.semiauto.deriveUnwrappedCodec

}
