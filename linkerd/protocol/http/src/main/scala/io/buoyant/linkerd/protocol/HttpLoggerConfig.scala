package io.buoyant.linkerd.protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Filter, Service, ServiceFactory, Stack, Stackable}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.config.PolymorphicConfig

abstract class HttpLoggerConfig extends PolymorphicConfig {
  @JsonIgnore
  def mk(): Filter[Request, Response, Request, Response]
}

object HttpLoggerConfig {
  object param {
    case class Logger(logger: Filter[Request, Response, Request, Response])
    implicit object Logger extends Stack.Param[Logger] {
      val default = Logger(Filter.identity)
    }
  }

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[HttpLoggerConfig.param.Logger, ServiceFactory[Request, Response]] {
      val role = Stack.Role("LOGGER")
      val description = "logger desc"
      def make(loggerP: HttpLoggerConfig.param.Logger, factory: ServiceFactory[Request, Response]): ServiceFactory[Request, Response] =
        loggerP.logger.andThen(factory)
    }
}
