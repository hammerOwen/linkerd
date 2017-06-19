package io.buoyant.k8s.istio

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.google.local.DurationProto.Duration
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.Service
import com.twitter.util.Future
import istio.mixer.v1.{Attributes, Mixer, ReportRequest, ReportResponse, StringMap}
import scala.io.Source

private[istio] object MixerClient {

  private[this] lazy val globalDict: Seq[String] = {
    val yaml = Source.fromInputStream(
      getClass.getClassLoader.getResourceAsStream("mixer/v1/global_dictionary.yaml")
    ).mkString

    val mapper = new ObjectMapper(new YAMLFactory) with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    mapper.readValue[Seq[String]](yaml).asInstanceOf[Seq[String]]
  }

  private[this] def indexOf(dict: Seq[String], str: String): Int =
    -1 * (dict.indexOf(str) + 1)

  // TODO: http1-only for now
  private[istio] def mkReportRequest(req: Request, rsp: Option[Response]): ReportRequest = {

    // TODO: build up this dictionary as requests are sent out

    // req.path
    val customWords = Seq[String](
      req.path,
      "buoyant.svc.cluster.local",
      "app",
      "SOURCE_LABELS_APP",
      "TARGET_LABELS_APP",
      "version",
      "TARGET_LABELS_VERSION"
    )

    // TODO: eventually we should not have to send globalDict over the wire,
    // and instead use positive index integers
    val defaultWords = globalDict ++ customWords

    // TODO: need to parse out:
    // target.service
    // source.labels[app] (or get source.uid)
    // target.labels[app] (or get target.uid)
    // target.labels[version] (or get target.uid)
    // response.duration (or calculate it here?)
    ReportRequest(
      globalWordCount = Some(globalDict.length),
      defaultWords = defaultWords,
      attributes = Seq[Attributes](
        Attributes(
          // minimum set of attributes to generate the following metrics in mixer/prometheus:
          // - request_count
          // - request_duration_bucket
          // - request_duration_count
          // - request_duration_sum
          strings = Map[Int, Int](
            indexOf(defaultWords, "request.path") -> indexOf(defaultWords, req.path), // method in prom
            indexOf(defaultWords, "target.service") -> indexOf(defaultWords, "buoyant.svc.cluster.local") // target in prom
          ),
          int64s = Map[Int, Long](
            indexOf(defaultWords, "response.code") -> rsp.getOrElse(
              Response(com.twitter.finagle.http.Status.InternalServerError) // map exceptions to 500
            ).statusCode // response_code in prom
          ),
          // TODO: send source.uid instead of labels, Mixer will populate them for us
          stringMaps = Map[Int, StringMap](
            indexOf(defaultWords, "source.labels") ->
              StringMap(
                entries = Map[Int, Int](
                  indexOf(defaultWords, "app") -> indexOf(defaultWords, "SOURCE_LABELS_APP") // source in prom
                )
              ),
            indexOf(defaultWords, "target.labels") ->
              StringMap(
                entries = Map[Int, Int](
                  indexOf(defaultWords, "app") -> indexOf(defaultWords, "TARGET_LABELS_APP"), // service in prom
                  indexOf(defaultWords, "version") -> indexOf(defaultWords, "TARGET_LABELS_VERSION") // version in prom
                )
              )
          ),
          durations = Map[Int, Duration](
            indexOf(defaultWords, "response.duration") -> // request_duration_[bucket|count|sum] in prom
              Duration(
                seconds = Some(0L),
                nanos = Some(123000000)
              )
          )
        )
      )
    )
  }
}

case class MixerClient(
  service: Service[h2.Request, h2.Response]
) {
  import MixerClient._

  def logHttp(req: Request, rsp: Option[Response]): Future[ReportResponse] = {
    val reportRequest = mkReportRequest(req, rsp)
    val client = new Mixer.Client(service)
    client.report(reportRequest)
  }
}
