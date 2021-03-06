/*
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.sparta.serving.api.helpers

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.slf4j.SLF4JLogging
import akka.io.IO
import akka.routing.RoundRobinPool
import com.stratio.sparta.driver.service.StreamingContextService
import com.stratio.sparta.serving.api.actor._
import com.stratio.sparta.serving.core.actor.StatusActor.AddClusterListeners
import com.stratio.sparta.serving.core.actor.{RequestActor, FragmentActor, StatusActor}
import com.stratio.sparta.serving.core.config.SpartaConfig
import com.stratio.sparta.serving.core.config.SpartaConfig._
import com.stratio.sparta.serving.core.constants.{AkkaConstant, AppConstant}
import com.stratio.sparta.serving.core.curator.CuratorFactoryHolder
import spray.can.Http

import scala.util.{Failure, Success, Try}

/**
 * Helper with common operations used to create a Sparta context used to run the application.
 */
object SpartaHelper extends SLF4JLogging {

  implicit var system: ActorSystem = _

  /**
   * Initializes Sparta's akka system running an embedded http server with the REST API.
   *
   * @param appName with the name of the application.
   */
  def initSpartaAPI(appName: String): Unit = {
    if (SpartaConfig.mainConfig.isDefined && SpartaConfig.apiConfig.isDefined) {
      val curatorFramework = CuratorFactoryHolder.getInstance()
      log.info("Initializing Sparta Actors System ...")
      system = ActorSystem(appName, SpartaConfig.mainConfig)
      val statusActor = system.actorOf(Props(
        new StatusActor(curatorFramework)), AkkaConstant.StatusActorName)
      val fragmentActor = system.actorOf(Props(new FragmentActor(curatorFramework)), AkkaConstant.FragmentActorName)
      val policyActor = system.actorOf(Props(
        new PolicyActor(curatorFramework, statusActor)), AkkaConstant.PolicyActorName)
      val executionActor = system.actorOf(Props(new RequestActor(curatorFramework)), AkkaConstant.ExecutionActorName)
      val streamingContextService = StreamingContextService(curatorFramework, SpartaConfig.mainConfig)
      val streamingContextActor = system.actorOf(Props(
        new LauncherActor(streamingContextService, curatorFramework)), AkkaConstant.LauncherActorName)
      val pluginActor = system.actorOf(Props(new PluginActor()), AkkaConstant.PluginActorName)
      val driverActor = system.actorOf(Props(new DriverActor()), AkkaConstant.DriverActorName)

      implicit val actors = Map(
        AkkaConstant.StatusActorName -> statusActor,
        AkkaConstant.FragmentActorName -> fragmentActor,
        AkkaConstant.PolicyActorName -> policyActor,
        AkkaConstant.LauncherActorName -> streamingContextActor,
        AkkaConstant.PluginActorName -> pluginActor,
        AkkaConstant.DriverActorName -> driverActor,
        AkkaConstant.ExecutionActorName -> executionActor
      )

      val controllerActor = system.actorOf(Props(
        new ControllerActor(actors, curatorFramework)), AkkaConstant.ControllerActorName)

      if (isHttpsEnabled) loadSpartaWithHttps(controllerActor)
      else loadSpartaWithHttp(controllerActor)

      statusActor ! AddClusterListeners
    } else log.info("Sparta Configuration is not defined")
  }

  def loadSpartaWithHttps(controllerActor: ActorRef): Unit = {
    import com.stratio.sparkta.serving.api.ssl.SSLSupport._
    IO(Http) ! Http.Bind(controllerActor,
      interface = SpartaConfig.apiConfig.get.getString("host"),
      port = SpartaConfig.apiConfig.get.getInt("port")
    )
    log.info("Sparta Actors System initiated correctly")
  }

  def loadSpartaWithHttp(controllerActor: ActorRef): Unit = {
    IO(Http) ! Http.Bind(controllerActor,
      interface = SpartaConfig.apiConfig.get.getString("host"),
      port = SpartaConfig.apiConfig.get.getInt("port")
    )
    log.info("Sparta Actors System initiated correctly")
  }

  def isHttpsEnabled: Boolean =
    SpartaConfig.getSprayConfig match {
      case Some(config) =>
        Try(config.getValue("ssl-encryption")) match {
          case Success(value) =>
            "on".equals(value.unwrapped())
          case Failure(e) =>
            log.error("Incorrect value in ssl-encryption option, setting https disabled", e)
            false
        }
      case None =>
        log.warn("Impossible to get spray config, setting https disabled")
        false
    }
}
