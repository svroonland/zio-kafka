package zio.kafka.client

import org.apache.kafka.clients.consumer.OffsetAndTimestamp
import org.apache.kafka.common.{ PartitionInfo, TopicPartition }
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._
import zio.kafka.client.serde.Deserializer
import zio.stream._

import scala.collection.JavaConverters._

class Consumer[R, K, V] private (
  private val consumer: ConsumerAccess,
  private val settings: ConsumerSettings,
  private val runloop: Runloop
)(implicit keyDeserializer: Deserializer[R, K], valueDeserializer: Deserializer[R, V]) {
  def assignment: BlockingTask[Set[TopicPartition]] =
    consumer.withConsumer(_.assignment().asScala.toSet)

  def beginningOffsets(
    partitions: Set[TopicPartition],
    timeout: Duration = Duration.Infinity
  ): BlockingTask[Map[TopicPartition, Long]] =
    consumer.withConsumer(_.beginningOffsets(partitions.asJava, timeout.asJava).asScala.mapValues(_.longValue()).toMap)

  def endOffsets(
    partitions: Set[TopicPartition],
    timeout: Duration = Duration.Infinity
  ): BlockingTask[Map[TopicPartition, Long]] =
    consumer.withConsumer(_.endOffsets(partitions.asJava, timeout.asJava).asScala.mapValues(_.longValue()).toMap)

  def listTopics(timeout: Duration = Duration.Infinity): BlockingTask[Map[String, List[PartitionInfo]]] =
    consumer.withConsumer(_.listTopics(timeout.asJava).asScala.mapValues(_.asScala.toList).toMap)

  def offsetsForTimes(
    timestamps: Map[TopicPartition, Long],
    timeout: Duration = Duration.Infinity
  ): BlockingTask[Map[TopicPartition, OffsetAndTimestamp]] =
    consumer.withConsumer(_.offsetsForTimes(timestamps.mapValues(Long.box).asJava, timeout.asJava).asScala.toMap)

  def partitionedStream: ZStream[
    Clock with Blocking,
    Throwable,
    (TopicPartition, ZStreamChunk[R, Throwable, CommittableRecord[K, V]])
  ] =
    ZStream
      .fromQueue(runloop.deps.partitions)
      .unTake
      .map {
        case (tp, partition) =>
          val partitionStream =
            if (settings.perPartitionChunkPrefetch <= 0) partition
            else ZStreamChunk(partition.chunks.buffer(settings.perPartitionChunkPrefetch))

          tp -> partitionStream.mapM(CommittableRecord.deserialize[R, K, V](_))
      }

  def partitionsFor(topic: String, timeout: Duration = Duration.Infinity): BlockingTask[List[PartitionInfo]] =
    consumer.withConsumer(_.partitionsFor(topic, timeout.asJava).asScala.toList)

  def position(partition: TopicPartition, timeout: Duration = Duration.Infinity): BlockingTask[Long] =
    consumer.withConsumer(_.position(partition, timeout.asJava))

  def plainStream: ZStreamChunk[R with Clock with Blocking, Throwable, CommittableRecord[K, V]] =
    ZStreamChunk(partitionedStream.flatMapPar(Int.MaxValue)(_._2.chunks))

  def seek(partition: TopicPartition, offset: Long): BlockingTask[Unit] =
    consumer.withConsumer(_.seek(partition, offset))

  def seekToBeginning(partitions: Set[TopicPartition]): BlockingTask[Unit] =
    consumer.withConsumer(_.seekToBeginning(partitions.asJava))

  def seekToEnd(partitions: Set[TopicPartition]): BlockingTask[Unit] =
    consumer.withConsumer(_.seekToEnd(partitions.asJava))

  def subscribe(subscription: Subscription) =
    consumer.withConsumer { c =>
      subscription match {
        case Subscription.Pattern(pattern) => c.subscribe(pattern.pattern, runloop.deps.rebalanceListener)
        case Subscription.Topics(topics)   => c.subscribe(topics.asJava, runloop.deps.rebalanceListener)
      }
    }

  def subscription: BlockingTask[Set[String]] =
    consumer.withConsumer(_.subscription().asScala.toSet)

  def unsubscribe: BlockingTask[Unit] =
    consumer.withConsumer(_.unsubscribe())
}

object Consumer {
  def make[R, K, V](
    settings: ConsumerSettings
  )(
    implicit keyDeserializer: Deserializer[R, K],
    valueDeserializer: Deserializer[R, V]
  ): ZManaged[Clock with Blocking, Throwable, Consumer[R, K, V]] =
    for {
      wrapper <- ConsumerAccess.make(settings)
      deps <- Runloop.Deps.make(
               wrapper,
               settings.pollInterval,
               settings.pollTimeout
             )
      runloop <- Runloop(deps)
    } yield new Consumer(wrapper, settings, runloop)

  /**
   * Execute an effect for each record and commit the offset after processing
   *
   * This method is the easiest way of processing messages on a Kafka topic.
   *
   * Messages on a single partition are processed sequentially, while the processing of
   * multiple partitions happens in parallel.
   *
   * Messages are processed with 'at least once' consistency: it is not guaranteed that every message
   * that is processed by the effect has a corresponding offset commit before stream termination.
   *
   * Offsets are committed after execution of the effect. They are batched when a commit action is in progress
   * to avoid backpressuring the stream.
   *
   * The effect should must absorb any failures. Failures should be handled by retries or ignoring the
   * error, which will result in the Kafka message being skipped.
   *
   * Usage example:
   *
   * {{{
   * val settings: ConsumerSettings = ???
   * val subscription = Subscription.Topics(Set("my-kafka-topic"))
   *
   * val consumerIO = Consumer.consumeWith[Environment, String, String](settings, subscription) { case (key, value) =>
   *   // Process the received record here
   *   putStrLn(s"Received record: ${key}: ${value}")
   * }
   * }}}
   *
   * @param settings Settings for creating a [[Consumer]]
   * @param subscription Topic subscription parameters
   * @param f Function that returns the effect to execute for each message. It is passed the key and value
   * @tparam R Environment
   * @tparam K Type of keys (an implicit [[Deserializer]] should be in scope)
   * @tparam V Type of values (an implicit [[Deserializer]] should be in scope)
   * @return Effect that completes with a unit value only when interrupted. May fail when the [[Consumer]] fails.
   */
  def consumeWith[R, K, V](
    subscription: Subscription,
    settings: ConsumerSettings
  )(f: (K, V) => ZIO[R, Nothing, Unit])(
    implicit keyDeserializer: Deserializer[R, K],
    valueDeserializer: Deserializer[R, V]
  ): ZIO[R with Clock with Blocking, Throwable, Unit] =
    ZStream
      .managed(Consumer.make[R, K, V](settings))
      .flatMap { consumer =>
        ZStream
          .fromEffect(consumer.subscribe(subscription))
          .flatMap { _ =>
            consumer.partitionedStream
              .flatMapPar(Int.MaxValue, outputBuffer = settings.perPartitionChunkPrefetch) {
                case (_, partitionStream) =>
                  partitionStream.mapM {
                    case CommittableRecord(record, offset) =>
                      f(record.key(), record.value()).as(offset)
                  }.flattenChunks
              }
          }
      }
      .aggregate(ZSink.foldLeft[Offset, OffsetBatch](OffsetBatch.empty)(_ merge _))
      .mapM(_.commit)
      .runDrain
}
