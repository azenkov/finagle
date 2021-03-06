package com.twitter.finagle.service

import com.twitter.finagle.stats.{Counter, StatsReceiver}
import com.twitter.finagle.util.AsyncLatch
import com.twitter.finagle.{param, Service, ServiceFactory, ServiceProxy, Stack, Stackable}
import com.twitter.util.{Duration, Promise, Future, NullTimerTask, Timer, Time}
import java.util.concurrent.atomic.AtomicBoolean

private[finagle] object ExpiringService {
  val role = Stack.Role("Expiration")

  /**
   * A class eligible for configuring a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.server.ExpiringService]].
   *
   * @param idleTime max duration for which a connection is allowed to be idle.
   * @param lifeTIme max lifetime of a connection.
   */
  case class Param(idleTime: Duration, lifeTime: Duration)
  implicit object Param extends Stack.Param[Param] {
    val default = Param(Duration.Top, Duration.Top)
  }

  /**
   * Creates a [[com.twitter.finagle.Stackable]] [[com.twitter.finagle.service.ExpiringService]].
   */
  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Simple[ServiceFactory[Req, Rep]] {
      val role = ExpiringService.role
      val description = "Expire a service after a certain amount of idle time"
      def make(next: ServiceFactory[Req, Rep])(implicit params: Params) = {
        val param.Timer(timer) = get[param.Timer]
        val ExpiringService.Param(idleTime, lifeTime) = get[ExpiringService.Param]
        val param.Stats(statsReceiver) = get[param.Stats]

        val idle = if (idleTime.isFinite) Some(idleTime) else None
        val life = if (lifeTime.isFinite) Some(lifeTime) else None

        (idle, life) match {
          case (None, None) => next
          case _ => next map { service =>
            val closeOnRelease = new CloseOnReleaseService(service)
            new ExpiringService(closeOnRelease, idle, life, timer, statsReceiver) {
              def onExpire() { closeOnRelease.close() }
            }
          }
        }
      }
    }
}

/**
 * A service wrapper that expires the self service after a
 * certain amount of idle time. By default, expiring calls
 * ``.close()'' on the self channel, but this action is
 * customizable.
 */
abstract class ExpiringService[Req, Rep](
  self: Service[Req, Rep],
  maxIdleTime: Option[Duration],
  maxLifeTime: Option[Duration],
  timer: Timer,
  stats: StatsReceiver
) extends ServiceProxy[Req, Rep](self)
{
  private[this] var active = true
  private[this] val latch = new AsyncLatch

  private[this] val idleCounter = stats.counter("idle")
  private[this] val lifeCounter = stats.counter("lifetime")
  private[this] var idleTask = startTimer(maxIdleTime, idleCounter)
  private[this] var lifeTask = startTimer(maxLifeTime, lifeCounter)
  private[this] val expireFnCalled = new AtomicBoolean(false)
  private[this] val didExpire = new Promise[Unit]

  private[this] def startTimer(duration: Option[Duration], counter: Counter) =
    duration map { t: Duration =>
      timer.schedule(t.fromNow) { expire(counter) }
    } getOrElse { NullTimerTask }

  private[this] def expire(counter: Counter) {
    if (deactivate()) {
      latch.await {
        expired()
        counter.incr()
      }
    }
  }

  private[this] def deactivate(): Boolean = synchronized {
    if (!active) false else {
      active = false
      idleTask.cancel()
      lifeTask.cancel()
      idleTask = NullTimerTask
      lifeTask = NullTimerTask
      true
    }
  }

  private[this] def expired(): Unit = {
    if (expireFnCalled.compareAndSet(false, true)) {
      didExpire.setDone()
      onExpire()
    }
  }

  protected def onExpire()

  override def apply(req: Req): Future[Rep] = {
    val decrLatch = synchronized {
      if (!active) false else {
        if (latch.incr() == 1) {
          idleTask.cancel()
          idleTask = NullTimerTask
        }
        true
      }
    }

    super.apply(req) ensure {
      if (decrLatch) {
        val n = latch.decr()
        synchronized {
          if (n == 0 && active)
            idleTask = startTimer(maxIdleTime, idleCounter)
        }
      }
    }
  }

  override def close(deadline: Time): Future[Unit] = {
    deactivate()
    expired()
    didExpire
  }
}
