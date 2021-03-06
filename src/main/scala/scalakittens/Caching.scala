package scalakittens

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import ref.{WeakReference, Reference, SoftReference}
import annotation.tailrec

/**
 * This trait/object's purpose is to be used for caching:
 * - individual instances
 * - which are hard to get (takes time)
 * - which are valid for a limited amount of time, and can be retrieved again
 * - which are not a burden to store in memory, say, in a map, say, in a ConcurrentHashMap
 *
 * Use something else if you have different requriements.
 * Or just let me know; this is work in progress. We had a bunch of caching stuff at Google, seems like now Google Guava
 * has cache package added. But that's Java, the thing of the past.
 *
 * The trick here is this: I use both AtomicReference and Scala's lazy val to store an expiring value and retrieve it only
 * once and only on demand.
 *
 * The algorithm is this. A class named Container stores the following data:
 * - a function that retrieves the value
 * - timestamp of a moment a new instance of CacheUnit was created (nanos)
 * - a lazy value that is cached; it is evaluated when the value is being retrieved, and Scala's lazy val mechanism
 *   (based on 'synchronized'), ensures that only one instance is evaluated.
 * An atomic reference to this Container is stored in an instance of CacheUnit; CacheUnit is the entity returned when
 * you create it, and it is immutable (with caveats).
 *
 * CacheUnit's apply() method returns the fresh value; if the value stored in current Container is not fresh, it's reevaluated.
 *
 * You can find use cases in the test. Here's one:
 * <code>
 *    var counter: Char = 'a'
 *    val cached = sut.cache(() => { counter = (counter+1).toChar; counter}) validFor 10 HOURS
 *</code>
 * We declare a cache that invalidates after 10 hours and retrieves a counter value, with a side-effect of bumping the counter.
 */
trait Caching {

  // indirection that is good for applying cake pattern: see the object below and the test where time is mocked
  protected def now: Long // expecting nanos

  class Container[T](fun:() => T, validUntil_nano: Long) {
    private lazy val value: T = fun()
    def stillValid = now < validUntil_nano
    def get: Option[T] = if (stillValid) Some(value) else None
  }

  trait RefFactory { def apply[X <: AnyRef] (x: X) : Reference[X] }
  
  class CacheUnit[T](fun:() => T, timeout_nano: Long, newRef: RefFactory) {

    private def newHolder = newRef(new Container[T](fun, now + timeout_nano))

    private val atom = new AtomicReference[Reference[Container[T]]](newHolder)

    @tailrec private def getValue: T = {
      val latest = atom.get
      latest.get.flatMap(_.get) match {
        case Some(t) => t
        case None    => atom.compareAndSet(latest, newHolder)
                        getValue
      }
    }

    def apply(): T = getValue

    def withTimeout(timeout: Long, unit: TimeUnit): CacheUnit[T] = new CacheUnit[T](fun, unit toNanos timeout, newRef)

    /**
     * Specify the time it is valid for, with units
     * @param timeout units are specified further down
     * @return a half-way object that needs a time unit to produce a cache unit (hope you follow me)
     */
    def validFor(timeout: Long) = new {
      def NANOSECONDS  = apply(TimeUnit.NANOSECONDS)
      def MICROSECONDS = apply(TimeUnit.MICROSECONDS)
      def MILLISECONDS = apply(TimeUnit.MILLISECONDS)
      def SECONDS      = apply(TimeUnit.SECONDS)
      def MINUTES      = apply(TimeUnit.MINUTES)
      def HOURS        = apply(TimeUnit.HOURS)
      def DAYS         = apply(TimeUnit.DAYS)
      private def apply(unit: TimeUnit) = withTimeout(timeout, unit)
    }

    def withHardReferences = new CacheUnit[T](fun, timeout_nano, HardFactory)
    def withSoftReferences = new CacheUnit[T](fun, timeout_nano, SoftFactory)
    def withWeakReferences = new CacheUnit[T](fun, timeout_nano, WeakFactory)
  }

  private object HardFactory extends RefFactory { def apply[X <: AnyRef](x: X) = new HardReference[X](x) }
  private object SoftFactory extends RefFactory { def apply[X <: AnyRef](x: X) = new SoftReference[X](x) }
  private object WeakFactory extends RefFactory { def apply[X <: AnyRef](x: X) = new WeakReference[X](x) }

  private class HardReference[+X <: AnyRef](x:X) extends Reference[X] {
    def apply() = x
    def get = Some(x)
    def isEnqueued() = false
    def enqueue() = false
    def clear() {}
  }

  def cache[T](fun: () => T) = new CacheUnit[T](fun, Long.MaxValue, HardFactory) // by default it's valid forever
}

object Caching extends Caching {
  protected def now = System.nanoTime

  implicit def currentValue[T](cu: CacheUnit[T]): T = cu.apply()
}