package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Address, Filter, Name, Service, Stack}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Return, Time, Throw}
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.MixerClient
import io.buoyant.linkerd.LoggerInitializer
import io.buoyant.linkerd.protocol.HttpLoggerConfig

class IstioLogger(mixerClient: MixerClient, params: Stack.Params) extends Filter[Request, Response, Request, Response] {

  private[this] val log = Logger.get()

  def apply(req: Request, svc: Service[Request, Response]) = {

    log.debug(s"IstioLogger.params: $params")
    log.debug(s"IstioLogger.req: $req")

    val start = Time.now

    svc(req).respond { ret =>
      val duration = Time.now - start

      val responseCode = {
        ret match {
          case Return(rsp) =>
            log.debug(s"IstioLogger.rsp: $rsp")
            rsp
          case Throw(e) =>
            // map exceptions to 500
            log.debug(s"IstioLogger exception: $e")
            Response(com.twitter.finagle.http.Status.InternalServerError)
        }
      }.statusCode

      // TODO: parse out targetService, targetLabelApp, targetLabelVersion
      val _ = mixerClient.report(
        responseCode,
        req.path,
        "buoyant.svc.cluster.local",
        s"${req.remoteHost}:${req.remotePort}",
        "TARGET_LABELS_APP",
        "TARGET_LABELS_VERSION",
        duration
      )
    }
  }
}

case class IstioLoggerConfig(
  mixerHost: Option[String],
  mixerPort: Option[Port]
) extends HttpLoggerConfig {
  @JsonIgnore
  val DefaultMixerHost = "istio-mixer.default.svc.cluster.local"
  @JsonIgnore
  val DefaultMixerPort = 9091

  @JsonIgnore
  private[this] val log = Logger.get("IstioLoggerConfig")

  val host = mixerHost.getOrElse(DefaultMixerHost)
  val port = mixerPort.map(_.port).getOrElse(DefaultMixerPort)
  log.info(s"connecting to Istio Mixer at $host:$port")
  val mixerDst = Name.bound(Address(host, port))
  val mixerService = H2.client
    .withParams(H2.client.params)
    .newService(mixerDst, "istioLogger")
  val client = new MixerClient(mixerService)

  def mk(params: Stack.Params): Filter[Request, Response, Request, Response] = {
    new IstioLogger(client, params)
  }
}

class IstioLoggerInitializer extends LoggerInitializer {
  val configClass = classOf[IstioLoggerConfig]
  override val configId = "io.l5d.istio"
}
