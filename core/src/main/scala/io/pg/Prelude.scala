package io.pg

import cats.effect.Resource
import cats.syntax.functor._
import cats.Applicative

object Prelude {

  type @@[A, B] = A

  implicit class EffectToResourceLiftSyntax[F[_], A](private val fa: F[A])
    extends AnyVal {

    def resource(implicit F: Applicative[F]): Resource[F, A] =
      Resource.liftF(fa)

    def resource_(implicit F: Applicative[F]): Resource[F, Unit] =
      fa.void.resource

  }

  implicit class AnythingAnything[A](private val a: A) extends AnyVal {
    def ??? : Nothing = ???
  }

}
