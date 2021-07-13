package io.pg.config

sealed trait TextMatcher extends Product with Serializable

object TextMatcher {
  final case class Equals(value: String) extends TextMatcher
  final case class Matches(pattern: String) extends TextMatcher
}

sealed trait Matcher extends Product with Serializable {
  def and(another: Matcher): Matcher = Matcher.Many(List(this, another))
  def or(another: Matcher): Matcher = Matcher.OneOf(List(this, another))
}

object Matcher {
  final case class Author(email: TextMatcher) extends Matcher
  final case class Description(text: TextMatcher) extends Matcher
  final case class PipelineStatus(status: String) extends Matcher
  final case class Many(values: List[Matcher]) extends Matcher
  final case class OneOf(values: List[Matcher]) extends Matcher
  final case class Not(underlying: Matcher) extends Matcher

  val always: Matcher = Many(Nil)
}
