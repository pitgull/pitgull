package io.pg.nix

import cats.tagless.autoContravariant

// A minimal Nix expression AST for our needs
sealed trait Nix extends Product with Serializable {
  def at(key: String): Map[RecordEntry, Nix] = Map(RecordEntry(key) -> this)
  def imported: Nix.Import = Nix.Import(this)
  def applied(arg: Nix): Nix.Apply = Nix.Apply(this, arg)
  def select(key: String): Nix.Select = Nix.Select(this, key)

  def render: String = Nix.render(this)
}

object Nix {
  final case class Import(source: Nix) extends Nix
  final case class Record(entries: Map[RecordEntry, Nix]) extends Nix
  final case class Name(value: String) extends Nix
  final case class Select(selectee: Nix, selector: String) extends Nix
  final case class Str(value: String) extends Nix
  final case class Apply(function: Nix, parameter: Nix) extends Nix

  @autoContravariant
  trait From[-A] {
    def toNix(a: A): Nix
  }

  object From {
    def apply[A](implicit A: From[A]): From[A] = A

    implicit val stringToNix: From[String] = Str(_)
    implicit val self: From[Nix] = identity(_)
  }

  object builtins {
    val fetchurl: Select = Name("builtins").select("fetchurl")
  }

  def obj(entries: (RecordEntry, Nix)*): Record = Record(entries.toMap)

  def render(expr: Nix): String = {
    def renderEntry(e: RecordEntry): String = e.key

    expr match {
      case Record(entries) => s"""{ ${entries.map { case (k, v) => s"${renderEntry(k)} = ${render(v)}; " }.mkString}}"""
      case Str(value)      =>
        val doubleTick = "''"
        val quote = "\""

        def escape(str: String) = str.replaceAll("\\\"", "\\\"")

        if (value.contains("\n")) doubleTick ++ value ++ doubleTick
        else quote ++ escape(value) ++ quote

      case Name(n)                    => n
      case Select(selectee, selector) =>
        val selecteeNeedsParens = selectee match {
          case Import(_) | Apply(_, _) => true
          case _                       => false
        }

        s"${parenthesizeOptional(selectee.render, selecteeNeedsParens)}.$selector"

      case Apply(function, arg) =>
        val functionNeedsParens = function match {
          case Import(_) => true
          case _         => false
        }

        val maybeWrapFunction = parenthesizeOptional(function.render, functionNeedsParens)
        val maybeWrapArg = parenthesizeOptional(arg.render, needsParensGeneric(arg))

        s"$maybeWrapFunction $maybeWrapArg"

      case Import(source) =>
        s"import ${parenthesizeOptional(source.render, needsParensGeneric(source))}"
    }
  }

  private val needsParensGeneric: Nix => Boolean = {
    case Import(_) | Apply(_, _) => true
    case _                       => false
  }

  private def parenthesizeOptional(s: String, shouldParenthesize: Boolean): String = if (shouldParenthesize) s"($s)" else s

  object syntax {

    implicit final class AnyToNix[A](private val self: A) extends AnyVal {
      def toNix(implicit F: From[A]): Nix = F.toNix(self)
    }

    implicit final class StringToNixKey(private val key: String) extends AnyVal {
      def :=[A: From](value: A): (RecordEntry, Nix) = RecordEntry(key) -> value.toNix
    }

  }

}

final case class RecordEntry(key: String)
