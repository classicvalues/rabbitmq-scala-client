package com.avast.clients.rabbitmq

import com.avast.metrics.test.NoOpMonitor
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.impl.recovery.AutorecoveringChannel
import org.mockito.Mockito._
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually
import org.scalatest.mock.MockitoSugar

import scala.util.Random

class DefaultRabbitMQProducerTest extends FunSuite with MockitoSugar with Eventually {
  test("basic") {
    val exchangeName = Random.nextString(10)
    val routingKey = Random.nextString(10)

    val channel = mock[AutorecoveringChannel]

    val producer = new DefaultRabbitMQProducer(
      name = "test",
      exchangeName = exchangeName,
      channel = channel,
      monitor = NoOpMonitor.INSTANCE
    )

    val properties = new AMQP.BasicProperties.Builder()
      .build()

    val body = Random.nextString(10).getBytes

    producer.send(routingKey, body, properties)

    verify(channel, times(1)).basicPublish(exchangeName, routingKey, properties, body)
  }
}