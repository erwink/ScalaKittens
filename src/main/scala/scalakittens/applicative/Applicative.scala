package scalakittens
package applicative

trait Applicative[T[_]] extends Functor[T] { self =>
  def pure[A](a: A): T[A]

//  the following is the formal declaration
//  def ap[A, B](tf: T[A => B]): T[A] => T[B]

// below are additional enrichments, not exactly pertaining to Applicative

  implicit def applicable[A, B](tf: T[A => B]): Applicable[A, B, T]

  def ap[A, B](fs: T[A => B]) = (ta: T[A]) => fs <*> ta

  trait Lifted[A, B, T[_]] { def <@>(ta: T[A]): T[B] }

  implicit def lift[A, B](fun: A => B) = new Lifted[A, B, T] {
    def <@>(ta: T[A]) = pure(fun) <*> ta
  }

  def andThen[U[_]](u: Applicative[U]) = new Applicative[({type λ[α] = U[T[α]]})#λ] {
    type λ[α] = U[T[α]]
    //override type f0[A] = U[T[A]] -- this is an issue
    def f1[A, B](f: A => B): (U[T[A]]) => U[T[B]] = u.f1(self.f1(f)) // have to figure out how not to repeat

    def pure[A](a: A) : U[T[A]] = u.pure(self.pure(a))

    /**
     * Comments from sassa_nf@livejournal.com
     * u.ap is U[W=>Z]=>U[W]=>U[Z]
     * u.ap is U[X=>Y]=>U[X]=>U[Y]
     * u.pure is C=>U[C]
     * self.ap is T[A=>B]=>T[A]=>T[B]
     *
     * so, u.pure is T[A=>B]=>T[A]=>T[B] => U[T[A=>B]=>T[A]=>T[B]]
     *
     * and inner u.ap is U[T[A=>B]=>T[A]=>T[B]]=>U[T[A=>B]]=>U[T[A]=>T[B]]
     *
     * and outer u.ap is U[T[A]=>T[B]]=>U[T[A]]=>U[T[B]]
     */
    
    implicit def applicable[A, B](utf: U[T[A => B]]) = {
      val uta2tb: U[(T[A]) => T[B]] = u.f1(self.ap[A, B])(utf)
      new Applicable[A, B, λ] {
        def <*>(uta: λ[A]) = u.applicable(uta2tb) <*> uta
      }
    }
  }
}

trait Applicable[A, B, T[_]] { def <*>(ta: T[A]): T[B] }

