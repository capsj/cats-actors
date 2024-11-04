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

package com.suprnation.actor

import cats.effect._
import cats.implicits._
import com.suprnation.actor._
import com.suprnation.actor.fsm._
import com.suprnation.actor.Actor._
import scala.concurrent.duration._
/*
object ExampleFSMApp extends IOApp {

  sealed trait Request
  case object Start extends Request
  case object Stop extends Request

  sealed trait State

  case object Idle extends State

  case object Active extends State

  case class Data(counter: Int)

  override def run(args: List[String]): IO[ExitCode] = {
    val fsm: IO[ReplyingActor[IO, Request, List[Any]]] = FSM[IO, State, Data, Request, List[Any]]
      .withConfig(FSMConfig.withConsoleInformation[IO, State, Data, Request, List[Any]])
      .when(Idle) (stateManager => { case FSM.Event(Start, data) =>
        for {
          newState <- stateManager.goto(Active)
        } yield newState.using(data.copy(counter = data.counter + 1))
      })
      .when(Active) (stateManager => { case FSM.Event(Stop, data) =>
        for {
          newState <- stateManager.goto(Idle)
        } yield newState.using(data.copy(counter = data.counter + 1))
      })
      .onTransition {
        case (Idle, Active) => _ => IO.println("Transitioning from Idle to Active")
        case (Active, Idle) => _ => IO.println("Transitioning from Active to Idle")
      }
      .startWith(Idle, Data(0))
      .initialize

    ActorSystem[IO]("example-fsm-system").use { system =>
      for {
        fsmActor <- system.replyingActorOf[Request, List[Any]](fsm)
        _ <- fsmActor ! Start
        _ <- fsmActor ! Stop
        _ <- fsmActor ! Start
        _ <- fsmActor ! Stop
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }
}
 */
object AskError extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    ActorSystem[IO]("ask-error")
      .use { system =>
        for {
          errorActor <- system.replyingActorOf(new ReplyingActor[IO, Any, Int] {
            override def receive: ReplyingReceive[IO, Any, Int] =
              _ => IO.raiseError(new Exception("oops"))
          })

          response <- errorActor ? "hello?"
        } yield response
      }
      .recoverWith(ex =>
        IO.println(
          s"This should be 'oops' but is a 'BoxedUnit cannot be cast' exception: ${ex.getMessage}"
        )
      )
      .as(ExitCode.Success)
}

import com.suprnation.typelevel.actors.syntax.TypecheckSyntax._

object TypecheckedAsk extends IOApp {

  sealed trait Want
  case object Beer extends Want
  case object Water extends Want

  sealed trait Response
  case class Yes(count: Int) extends Response
  case object No extends Response

  override def run(args: List[String]): IO[ExitCode] =
    ActorSystem[IO]("typechecked ask")
      .use { system =>
        for {
          actor <- system.replyingActorOf(new ReplyingActor[IO, Want, Response] {
            override def receive: ReplyingReceive[IO, Want, Response] = {
              case Beer  => Yes(1).pure[IO]
              case Water => No.pure[IO]
            }
          })

          plainResponse <- actor ? Beer
          // typedResponse <- actor ?>[Yes] Beer
        } yield ExitCode.Success
      }
}

object TimerExample extends IOApp {
  trait Request
  case object Hello extends Request
  case object Enough extends Request

  case class TimerActor(
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

  override def run(args: List[String]): IO[ExitCode] =
    ActorSystem[IO]("Timer example")
      .use { system =>
        for {
          timerGenRef <- Timers.initGenRef[IO]
          timersRef <- Timers.initTimersRef[IO, String]
          actor <- system.actorOf(
            TimerActor(1.second, 5.seconds + 50.millis, timerGenRef, timersRef)
          )
          _ <- IO.sleep(6.seconds)
        } yield ExitCode.Success
      }

}
