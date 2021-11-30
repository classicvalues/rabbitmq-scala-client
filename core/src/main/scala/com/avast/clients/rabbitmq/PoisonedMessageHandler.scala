package com.avast.clients.rabbitmq

import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import com.avast.clients.rabbitmq.PoisonedMessageHandler.defaultHandlePoisonedMessage
import com.avast.clients.rabbitmq.api.DeliveryResult.{Reject, Republish}
import com.avast.clients.rabbitmq.api.{Delivery, DeliveryResult}
import com.typesafe.scalalogging.StrictLogging

import scala.util.Try
import scala.util.control.NonFatal

sealed trait PoisonedMessageHandler[F[_], A] {
  def interceptResult(delivery: Delivery[A], result: DeliveryResult): F[DeliveryResult]
}

class LoggingPoisonedMessageHandler[F[_]: Sync, A](maxAttempts: Int) extends PoisonedMessageHandler[F, A] {
  override def interceptResult(delivery: Delivery[A], result: DeliveryResult): F[DeliveryResult] = {
    PoisonedMessageHandler.handleResult(delivery, maxAttempts, (d: Delivery[A], _) => defaultHandlePoisonedMessage[F, A](maxAttempts)(d))(
      result)
  }
}

class NoOpPoisonedMessageHandler[F[_]: Sync, A] extends PoisonedMessageHandler[F, A] {
  override def interceptResult(delivery: Delivery[A], result: DeliveryResult): F[DeliveryResult] = Sync[F].pure(result)
}

//trait PoisonedMessageHandler[F[_], A] extends (Delivery[A] => F[DeliveryResult])
//
//private[rabbitmq] class DefaultPoisonedMessageHandler[F[_]: Sync, A](maxAttempts: Int)(wrappedAction: Delivery[A] => F[DeliveryResult])
//    extends PoisonedMessageHandler[F, A]
//    with StrictLogging {
//
//  override def apply(delivery: Delivery[A]): F[DeliveryResult] = {
//    wrappedAction(delivery).flatMap(handleResult(delivery, maxAttempts, (d, _) => handlePoisonedMessage(d)))
//  }
//
//  /** This method logs the delivery by default but can be overridden. The delivery is always REJECTed after this method execution.
//    */
//  protected def handlePoisonedMessage(delivery: Delivery[A]): F[Unit] = defaultHandlePoisonedMessage(maxAttempts)(delivery)
//}

object PoisonedMessageHandler extends StrictLogging {
  final val RepublishCountHeaderName: String = "X-Republish-Count"

  private[rabbitmq] def make[F[_]: Sync, A](config: Option[PoisonedMessageHandlingConfig]): PoisonedMessageHandler[F, A] = {
    config match {
      case Some(value) => new LoggingPoisonedMessageHandler[F, A](3)
      case None => new NoOpPoisonedMessageHandler[F, A]
    }
  }

//  def apply[F[_]: Sync, A](maxAttempts: Int)(wrappedAction: Delivery[A] => F[DeliveryResult]): PoisonedMessageHandler[F, A] = {
//    new DefaultPoisonedMessageHandler[F, A](maxAttempts)(wrappedAction)
//  }
//
//  /**
//    * @param customPoisonedAction The delivery is always REJECTed after this method execution.
//    */
//  def withCustomPoisonedAction[F[_]: Sync, A](maxAttempts: Int)(wrappedAction: Delivery[A] => F[DeliveryResult])(
//      customPoisonedAction: Delivery[A] => F[Unit]): PoisonedMessageHandler[F, A] = {
//    new DefaultPoisonedMessageHandler[F, A](maxAttempts)(wrappedAction) {
//      override protected def handlePoisonedMessage(delivery: Delivery[A]): F[Unit] = customPoisonedAction(delivery)
//    }
//  }

  private[rabbitmq] def defaultHandlePoisonedMessage[F[_]: Sync, A](maxAttempts: Int)(delivery: Delivery[A]): F[Unit] = Sync[F].delay {
    logger.warn(s"Message failures reached the limit $maxAttempts attempts, throwing away: $delivery")
  }

  private[rabbitmq] def handleResult[F[_]: Sync, A](
      delivery: Delivery[A],
      maxAttempts: Int,
      handlePoisonedMessage: (Delivery[A], Int) => F[Unit])(r: DeliveryResult): F[DeliveryResult] = {
    r match {
      case Republish(isPoisoned, newHeaders) if isPoisoned => adjustDeliveryResult(delivery, maxAttempts, newHeaders, handlePoisonedMessage)
      case r => Applicative[F].pure(r) // keep other results as they are
    }
  }

  private def adjustDeliveryResult[F[_]: Sync, A](delivery: Delivery[A],
                                                  maxAttempts: Int,
                                                  newHeaders: Map[String, AnyRef],
                                                  handlePoisonedMessage: (Delivery[A], Int) => F[Unit]): F[DeliveryResult] = {
    // get current attempt no. from passed headers with fallback to original (incoming) headers - the fallback will most likely happen
    // but we're giving the programmer chance to programmatically _pretend_ lower attempt number
    val attempt = (delivery.properties.headers ++ newHeaders)
      .get(RepublishCountHeaderName)
      .flatMap(v => Try(v.toString.toInt).toOption)
      .getOrElse(0) + 1

    logger.debug(s"Attempt $attempt/$maxAttempts")

    if (attempt < maxAttempts) {
      Applicative[F].pure(
        Republish(isPoisoned = true, newHeaders = newHeaders + (RepublishCountHeaderName -> attempt.asInstanceOf[AnyRef])))
    } else {
      handlePoisonedMessage(delivery, maxAttempts)
        .recover {
          case NonFatal(e) =>
            logger.warn("Custom poisoned message handler failed", e)
            ()
        }
        .map(_ => Reject) // always REJECT the message
    }
  }

}
