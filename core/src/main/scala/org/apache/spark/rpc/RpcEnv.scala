/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.rpc

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import akka.actor.{ActorRef, Actor, Props, ActorSystem}
import akka.pattern.ask
import akka.remote.{DisassociatedEvent, RemotingLifecycleEvent}
import org.slf4j.Logger

import org.apache.spark.{Logging, SparkException, SparkConf}
import org.apache.spark.util.AkkaUtils


/**
 * An RPC environment.
 */
abstract class RpcEnv {
  def setupEndPoint(name: String, endpoint: RpcEndPoint): RpcEndPointRef

  def setupDriverEndPointRef(name: String): RpcEndPointRef

  def setupEndPointRefByUrl(url: String): RpcEndPointRef

  def stop(endpoint: RpcEndPointRef): Unit

  def stopAll(): Unit
}


/**
 * An end point for the RPC that defines what functions to trigger given a message.
 */
abstract class RpcEndPoint {

  def receive(sender: RpcEndPointRef): PartialFunction[Any, Unit]

  def remoteConnectionTerminated(remoteAddress: String): Unit = {
    // By default, do nothing.
  }

  protected def log: Logger

  private[rpc] def logMessage = log
}


/**
 * A reference for a remote [[RpcEndPoint]].
 */
abstract class RpcEndPointRef {

  def address: String

  def askWithReply[T](message: Any): T

  /**
   * Send a message to the remote end point asynchronously. No delivery guarantee is provided.
   */
  def send(message: Any): Unit
}


class AkkaRpcEnv(actorSystem: ActorSystem, conf: SparkConf) extends RpcEnv {

  override def setupEndPoint(name: String, endpoint: RpcEndPoint): RpcEndPointRef = {
    val actorRef = actorSystem.actorOf(Props(new Actor {
      override def preStart(): Unit = {
        // Listen for remote client disconnection events, since they don't go through Akka's watch()
        context.system.eventStream.subscribe(self, classOf[RemotingLifecycleEvent])
      }

      override def receive: Receive = {
        case DisassociatedEvent(_, remoteAddress, _) =>
          endpoint.remoteConnectionTerminated(remoteAddress.toString)

        case message: Any =>
          endpoint.logMessage.trace("Received RPC message: " + message)
          val pf = endpoint.receive(new AkkaRpcEndPointRef(sender(), conf))
          if (pf.isDefinedAt(message)) {
            pf.apply(message)
          }
      }
    }), name = name)
    new AkkaRpcEndPointRef(actorRef, conf)
  }

  override def setupDriverEndPointRef(name: String): RpcEndPointRef = {
    new AkkaRpcEndPointRef(AkkaUtils.makeDriverRef(name, conf, actorSystem), conf)
  }

  override def setupEndPointRefByUrl(url: String): RpcEndPointRef = {
    val timeout = Duration.create(conf.getLong("spark.akka.lookupTimeout", 30), "seconds")
    val ref = Await.result(actorSystem.actorSelection(url).resolveOne(timeout), timeout)
    new AkkaRpcEndPointRef(ref, conf)
  }

  override def stopAll(): Unit = {
    // Do nothing since actorSystem was created outside.
  }

  override def stop(endpoint: RpcEndPointRef): Unit = {
    require(endpoint.isInstanceOf[AkkaRpcEndPointRef])
    actorSystem.stop(endpoint.asInstanceOf[AkkaRpcEndPointRef].actorRef)
  }
}


class AkkaRpcEndPointRef(private[rpc] val actorRef: ActorRef, conf: SparkConf)
  extends RpcEndPointRef with Serializable with Logging {

  private[this] val maxRetries = conf.getInt("spark.akka.num.retries", 3)
  private[this] val retryWaitMs = conf.getInt("spark.akka.retry.wait", 3000)
  private[this] val timeout =
    Duration.create(conf.getLong("spark.akka.lookupTimeout", 30), "seconds")

  override def address: String = actorRef.path.address.toString

  override def askWithReply[T](message: Any): T = {
    var attempts = 0
    var lastException: Exception = null
    while (attempts < maxRetries) {
      attempts += 1
      try {
        val future = actorRef.ask(message)(timeout)
        val result = Await.result(future, timeout)
        if (result == null) {
          throw new SparkException("Actor returned null")
        }
        return result.asInstanceOf[T]
      } catch {
        case ie: InterruptedException => throw ie
        case e: Exception =>
          lastException = e
          logWarning("Error sending message in " + attempts + " attempts", e)
      }
      Thread.sleep(retryWaitMs)
    }

    throw new SparkException(
      "Error sending message [message = " + message + "]", lastException)
  }

  override def send(message: Any): Unit = {
    actorRef ! message
  }

  override def toString: String = s"${getClass.getSimpleName}($actorRef)"
}
