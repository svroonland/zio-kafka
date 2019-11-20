package zio.kafka.client

import zio.test._
import zio._
import zio.kafka.client.serde.Serde

object MultiConsumerTestHelper {
  import KafkaTestUtils._

  def makeTopic(name: String, nParts: Int) = withAdmin { admin =>
    admin.createTopic(AdminClient.NewTopic(name, nParts, 1))
  }

  case class UsefulInfo(consumerIndex: Int, topic: String, partition: Int, offset: Long, key: String, value: String)

  def consumeN(topic: String, groupId: String, consumerIndex: Int, nTakes: Int) =
    withConsumer(groupId, "client1") { consumer =>
      for {
        data <- consumer
                 .subscribeAnd(Subscription.Topics(Set(topic)))
                 .partitionedStream(Serde.string, Serde.string)
                 .flatMap(_._2.flattenChunks)
                 .take(nTakes)
                 .runCollect
                 .map(
                   x =>
                     x.map { item =>
                       UsefulInfo(
                         consumerIndex,
                         item.record.topic,
                         item.record.partition,
                         item.offset.offset,
                         item.record.key,
                         item.record.value
                       )
                     }
                 )
      } yield data
    }

  def makeMany(topic: String, howMany: Int) = {
    val many = 1.to(howMany).map { i =>
      val k = i // % 8
      (s"key-$k", s"value-$i")
    }
    produceMany(topic, many)
  }

  val testMultipleConsumers = testM("test multiple consumers") {
    for {
      topic           <- randomTopic
      consumerGroupId <- randomGroup
      _               <- makeTopic(topic, 5)
      _               <- makeMany(topic, 1000)
      consumed        = 0.to(4).map(i => MultiConsumerTestHelper.consumeN(topic, consumerGroupId, i, 3))
      _               <- ZIO.collectAll(consumed)
    } yield assertCompletes

  }
  val testParallelConsumers = testM("test parallel consumers") {
    for {
      topic           <- randomTopic
      consumerGroupId <- randomGroup
      _               <- makeTopic(topic, 5)
      _               <- makeMany(topic, 1000)
      consumed        = 0.to(4).map(i => MultiConsumerTestHelper.consumeN(topic, consumerGroupId, i, 3))
      _               <- ZIO.collectAllPar(consumed)
    } yield assertCompletes

  }
  val testSingleConsumerManyRecords = testM("test lots of stuff") {
    for {
      topic           <- randomTopic
      consumerGroupId <- randomGroup
      _               <- makeMany(topic, 100000)
      _               <- MultiConsumerTestHelper.consumeN(topic, consumerGroupId, 0, 100000)
    } yield assertCompletes

  }

}
