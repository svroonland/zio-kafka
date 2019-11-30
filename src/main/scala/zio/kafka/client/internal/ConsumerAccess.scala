package zio.kafka.client.internal

import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import zio._
import zio.blocking.{ blocking, Blocking }
import zio.kafka.client.{ BlockingTask, ConsumerSettings }
import zio.kafka.client.internal.ConsumerAccess.ByteArrayKafkaConsumer

import scala.jdk.CollectionConverters._

private[client] class ConsumerAccess(private[client] val consumer: ByteArrayKafkaConsumer, access: Semaphore) {
  def withConsumer[A](f: ByteArrayKafkaConsumer => A): BlockingTask[A] =
    withConsumerM[Any, A](c => ZIO(f(c)))

  def withConsumerM[R, A](f: ByteArrayKafkaConsumer => ZIO[R, Throwable, A]): ZIO[R with Blocking, Throwable, A] =
    access.withPermit(withConsumerNoPermit(f))

  private[client] def withConsumerNoPermit[R, A](
    f: ByteArrayKafkaConsumer => ZIO[R, Throwable, A]
  ): ZIO[R with Blocking, Throwable, A] =
    blocking(ZIO.effectSuspend(f(consumer))).catchSome {
      case _: WakeupException => ZIO.interrupt
    }.fork.flatMap { fib =>
      fib.join.onInterrupt(ZIO.effectTotal(consumer.wakeup()) *> fib.interrupt)
    }
}

private[client] object ConsumerAccess {
  type ByteArrayKafkaConsumer = KafkaConsumer[Array[Byte], Array[Byte]]

  def make(settings: ConsumerSettings) =
    for {
      access <- Semaphore.make(1).toManaged_
      consumer <- blocking {
                   ZIO {
                     new KafkaConsumer[Array[Byte], Array[Byte]](
                       settings.driverSettings.asJava,
                       new ByteArrayDeserializer(),
                       new ByteArrayDeserializer()
                     )
                   }
                 }.toManaged { c =>
                   blocking(access.withPermit(UIO(c.close(settings.closeTimeout.asJava))))
                 }
    } yield new ConsumerAccess(consumer, access)
}
