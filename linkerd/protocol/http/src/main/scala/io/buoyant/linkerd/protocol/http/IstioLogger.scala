package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.{Address, Filter, Name, Service}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Return, Throw}
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.MixerClient
import io.buoyant.linkerd.LoggerInitializer
import io.buoyant.linkerd.protocol.HttpLoggerConfig

class IstioLogger(mixerClient: MixerClient) extends Filter[Request, Response, Request, Response] {

  def apply(req: Request, svc: Service[Request, Response]) = {
    printf(s"""IstioLogger1: $req"""")
    svc(req).respond {
      case Throw(e) =>
        printf(s"""IstioLogger2 FAILS: $req => $e"""")
        val _ = mixerClient.logHttp(req, None)
      case Return(rsp) =>
        printf(s"""IstioLogger2 SUCCESS: $req => $rsp"""")
        val _ = mixerClient.logHttp(req, Some(rsp))
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

  def mk(): Filter[Request, Response, Request, Response] = {
    val host = mixerHost.getOrElse(DefaultMixerHost)
    val port = mixerPort.map(_.port).getOrElse(DefaultMixerPort)

    log.info(s"connecting to Istio Mixer at $host:$port")
    val mixerDst = Name.bound(Address(host, port))
    val mixerService = H2.client
      .withParams(H2.client.params)
      .newService(mixerDst, "istioLogger")
    val client = new MixerClient(mixerService)

    new IstioLogger(client)
  }
}

class IstioLoggerInitializer extends LoggerInitializer {
  val configClass = classOf[IstioLoggerConfig]
  override val configId = "io.l5d.istio"
}
