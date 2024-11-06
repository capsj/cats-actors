/*
 * Copyright 2024 SuprNation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.suprnation.actor.timers

import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Ref}
import com.suprnation.actor.Actor.{Actor, Receive, ReplyingReceive}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.SupervisorStrategy.{Restart, Stop}
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.debug.TrackingActor.ActorRefs
import com.suprnation.actor.dungeon.TimerSchedulerImpl
import com.suprnation.actor.dungeon.TimerSchedulerImpl.{StoredTimer, TimerMode}
import com.suprnation.actor.event.Debug
import com.suprnation.actor.test.TestKit
import com.suprnation.actor.timers.TimersTest._
import com.suprnation.actor.{
  ActorSystem,
  DeadLetter,
  OneForOneStrategy,
  ReplyingActor,
  ReplyingActorRef,
  SupervisionStrategy,
  Timers
}
import com.suprnation.typelevel.actors.syntax.{ActorSyntaxFOps, ActorSystemDebugOps}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class TimerSpec extends AsyncFlatSpec with Matchers with TestKit {

  val startTimerA: StartTimer = StartTimer(KeyA, 100.millis, TimerMode.Single)
  val counterAddA: CounterAdd = CounterAdd(KeyA)

  it should "schedule a single message" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(_, parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          expectMsgs(trackerActor, 150.millis)(counterAddA)
      }

    count shouldEqual 1
  }

  it should "schedule repeated messages" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(actorSystem, parentActor, trackerActor) =>
        (parentActor ! StartTimer(KeyA, 150.millis, TimerMode.FixedDelay)) >>
          expectMsgs(trackerActor, 450.millis)(ArraySeq.fill(3)(counterAddA): _*) >>
          (parentActor ! CancelTimer(KeyA)) >>
          actorSystem.waitForIdle(maxTimeout = 500.millis).void
      }

    println(s"Received final count: ${count}")
    count shouldEqual 3
  }

  it should "replace timers with the same key" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(_, parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! startTimerA) >>
          (parentActor ! startTimerA) >>
          expectMsgs(trackerActor, 150.millis)(counterAddA)
      }

    count shouldEqual 1
  }

  it should "support timers with different keys" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(_, parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! StartTimer(KeyB, 150.millis, TimerMode.Single)) >>
          expectMsgs(trackerActor, 200.millis)(counterAddA, CounterAdd(KeyB))
      }

    count shouldEqual 2
  }

  it should "cancel timers" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(_, parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! CancelTimer(KeyA)) >>
          expectNoMsg(trackerActor, 150.millis)
      }

    count shouldEqual 0
  }

  it should "cancel all timers on restart" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(_, parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! RestartCommand) >>
          expectNoMsg(trackerActor, 150.millis)
      }

    count shouldEqual 0
  }

  it should "cancel all timers on stop" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(_, parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! StopCommand) >>
          expectNoMsg(trackerActor, 150.millis)
      }

    count shouldEqual 0
  }

  it should "support replying actors" in {
    val count: Int =
      fixture(ReplyingTimedActor.apply) { case FixtureParams(_, parentActor, trackerActor) =>
        for {
          reply <- parentActor ? startTimerA
          _ <- expectMsgs(trackerActor, 150.millis)(counterAddA)
        } yield reply shouldEqual Ok
      }

    count shouldEqual 1
  }

  it should "support tracking actor" in {
    ActorSystem[IO]()
      .use { actorSystem =>
        for {
          timerGenRef <- Timers.initGenRef[IO]
          timersRef <- Timers.initTimersRef[IO, String]
          cache <- ActorRefs
            .empty[IO]
            .map(
              _.copy(
                timerGenRef = timerGenRef,
                timersRef = timersRef
              )
            )
          stableName = "tracker"
          cacheMap <- Ref.of[IO, Map[String, ActorRefs[IO]]](Map(stableName -> cache))
          actorRef <- actorSystem.actorOf(
            TrackingActor.create[IO, Any, Any](
              cache = cacheMap,
              stableName = stableName,
              proxy =
                IndependentTimerActor(100.millis, 550.millis, timerGenRef, timersRef).widen[Any]
            )
          )

          result <-
            for {
              receivedMessage <- receiveWhile(actorRef, 1.second) { case req: Request =>
                req
              }
            } yield {
              receivedMessage.distinct should have size 2
              receivedMessage shouldBe Seq(Hello, Hello, Hello, Hello, Hello, Enough)
            }

        } yield result
      }
      .unsafeToFuture()
  }

  private case class FixtureParams[Response](
      actorSystem: ActorSystem[IO],
      parentActor: ReplyingActorRef[IO, TimerMsg, Response],
      trackerActor: ActorRef[IO, Any]
  )

  private def fixture[Response](
      createF: (
          Ref[IO, Int],
          ActorRef[IO, Any],
          Ref[IO, Int],
          Ref[IO, Map[Key, StoredTimer[IO]]]
      ) => ReplyingActor[IO, TimerMsg, Response]
  )(test: FixtureParams[Any] => IO[Unit]): Int = {

    def deadLetterListener(countRef: Ref[IO, Int]): Any => IO[Unit] = {
      case Debug(_, _, DeadLetter(_, _, _)) =>
        IO.println("DeadLetter received. Failing test") >> countRef.set(-1)
      case _ => IO.unit
    }

    Ref
      .empty[IO, Int]
      .flatMap { countRef =>
        ActorSystem[IO]("timers", deadLetterListener(countRef))
          .use { system =>
            for {
              trackerActor <-
                system.actorOf(
                  ReplyingActor
                    .ignoring[IO, Any]("Timers test tracker actor")
                    .trackWithCache("Timers test tracker actor")
                )
              timerGenRef <- Timers.initGenRef[IO]
              timersRef <- Timers.initTimersRef[IO, Key]
              timedActorRef <- Ref.of[IO, Option[TimedActorRef[Response]]](None)
              parentActor <-
                system.replyingActorOf(
                  new ParentActor(timedActorRef) {
                    override def create: ReplyingActor[IO, TimerMsg, Response] =
                      createF.apply(countRef, trackerActor, timerGenRef, timersRef)
                  }
                )
              _ <- test(FixtureParams(system, parentActor, trackerActor))
              count <- countRef.get
            } yield count
          }
      }
      .unsafeRunSync()
  }
}

object TimersTest {
  sealed trait TimerMsg
  case class StartTimer(key: Key, delay: FiniteDuration, mode: TimerMode) extends TimerMsg
  case class CancelTimer(key: Key) extends TimerMsg
  case class CounterAdd(source: Key) extends TimerMsg
  case object RestartCommand extends TimerMsg
  case object StopCommand extends TimerMsg

  case object RestartException extends Exception("restart!")
  case object StopException extends Exception("stop!")

  sealed trait Key
  case object KeyA extends Key
  case object KeyB extends Key

  sealed trait TimerResponse
  case object Ok extends TimerResponse
  case object NotOk extends TimerResponse

  case class TimedActor(
      countRef: Ref[IO, Int],
      trackerActor: ActorRef[IO, Any],
      timerGenRef: Ref[IO, Int],
      timersRef: Ref[IO, Map[Key, TimerSchedulerImpl.StoredTimer[IO]]]
  ) extends Actor[IO, TimerMsg]
      with Timers[IO, TimerMsg, Any, Key] {
    override val asyncEvidence: Async[IO] = implicitly[Async[IO]]

    override def receive: Receive[IO, TimerMsg] = {
      case StartTimer(key, delay, TimerMode.Single) =>
        timers.startSingleTimer(key, CounterAdd(key), delay)
      case StartTimer(key, delay, TimerMode.FixedDelay) =>
        timers.startTimerWithFixedDelay(key, CounterAdd(key), delay)
      case CancelTimer(key) => timers.cancel(key)
      case msg: CounterAdd  => countRef.update(_ + 1) >> (trackerActor ! msg)
      case RestartCommand   => IO.raiseError(RestartException)
      case StopCommand      => IO.raiseError(StopException)
    }
  }

  case class ReplyingTimedActor(
      countRef: Ref[IO, Int],
      trackerActor: ActorRef[IO, Any],
      timerGenRef: Ref[IO, Int],
      timersRef: Ref[IO, Map[Key, TimerSchedulerImpl.StoredTimer[IO]]]
  ) extends ReplyingActor[IO, TimerMsg, TimerResponse]
      with Timers[IO, TimerMsg, TimerResponse, Key] {
    override val asyncEvidence: Async[IO] = implicitly[Async[IO]]

    override def receive: ReplyingReceive[IO, TimerMsg, TimerResponse] = {
      case StartTimer(key, delay, TimerMode.Single) =>
        timers.startSingleTimer(key, CounterAdd(key), delay).as(Ok)
      case StartTimer(key, delay, TimerMode.FixedDelay) =>
        timers.startTimerWithFixedDelay(key, CounterAdd(key), delay).as(Ok)
      case CancelTimer(key) => timers.cancel(key).as(Ok)
      case msg: CounterAdd  => (countRef.update(_ + 1) >> (trackerActor ! msg)).as(Ok)
      case RestartCommand   => IO.raiseError(RestartException).as(NotOk)
      case StopCommand      => IO.raiseError(StopException).as(NotOk)
    }
  }

  type TimedActorRef[Response] = ReplyingActorRef[IO, TimerMsg, Response]

  abstract class ParentActor[Response](timedActorRef: Ref[IO, Option[TimedActorRef[Response]]])
      extends ReplyingActor[IO, TimerMsg, Response] {
    override def preStart: IO[Unit] =
      getOrCreateChild >> super.preStart

    override def supervisorStrategy: SupervisionStrategy[IO] =
      OneForOneStrategy() {
        case RestartException => Restart
        case StopException    => Stop
      }

    override def receive: ReplyingReceive[IO, TimerMsg, Response] = { case msg =>
      getOrCreateChild.flatMap(_ ? msg)
    }

    def getOrCreateChild: IO[TimedActorRef[Response]] =
      for {
        childRefOpt <- timedActorRef.get
        ref <- childRefOpt.fold(context.replyingActorOf(create))(IO.pure)
        _ <- timedActorRef.set(Some(ref))
      } yield ref

    def create: ReplyingActor[IO, TimerMsg, Response]
  }

  trait Request
  case object Hello extends Request
  case object Enough extends Request

  case class IndependentTimerActor(
      interval: FiniteDuration,
      stopAfter: FiniteDuration,
      timerGenRef: Ref[IO, Int],
      timersRef: Ref[IO, Timers.TimerMap[IO, String]]
  ) extends Actor[IO, Request]
      with Timers[IO, Request, Any, String] {

    implicit def asyncEvidence: Async[IO] = cats.effect.IO.asyncForIO

    override def receive: Receive[IO, Request] = {
      case Hello =>
        IO.println(s"Hello, World! Another $interval has passed.")
      case Enough =>
        IO.println(s"Ok, that's enough") *>
          timers.cancel("My Timer!") *>
          context.stop(self)
    }

    override def preStart: IO[Unit] =
      timers.startTimerWithFixedDelay("My Timer!", Hello, interval) *>
        timers.startSingleTimer("Somebody stop me!", Enough, stopAfter)

  }
}
