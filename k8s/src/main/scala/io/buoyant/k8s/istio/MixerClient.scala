package io.buoyant.k8s.istio

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.google.local.DurationProto.{Duration => gDuration}
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.Service
import com.twitter.logging.Logger
import com.twitter.util.{Duration, Future}
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

  private[istio] val log = Logger()

  private[istio] def mkReportRequest(
    responseCode: Int,
    requestPath: String,
    targetService: String,
    sourceLabelApp: String,
    targetLabelApp: String,
    targetLabelVersion: String,
    duration: gDuration
  ): ReportRequest = {

    val customWords = Seq[String](
      "app",
      "version",
      requestPath,
      targetService,
      sourceLabelApp,
      targetLabelApp,
      targetLabelVersion
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
            indexOf(defaultWords, "request.path") -> indexOf(defaultWords, requestPath), // method in prom
            indexOf(defaultWords, "target.service") -> indexOf(defaultWords, targetService) // target in prom
          ),
          int64s = Map[Int, Long](
            indexOf(defaultWords, "response.code") -> responseCode // response_code in prom
          ),
          // TODO: send source.uid instead of labels, Mixer will populate them for us
          stringMaps = Map[Int, StringMap](
            indexOf(defaultWords, "source.labels") ->
              StringMap(
                entries = Map[Int, Int](
                  indexOf(defaultWords, "app") -> indexOf(defaultWords, sourceLabelApp) // source in prom
                )
              ),
            indexOf(defaultWords, "target.labels") ->
              StringMap(
                entries = Map[Int, Int](
                  indexOf(defaultWords, "app") -> indexOf(defaultWords, targetLabelApp), // service in prom
                  indexOf(defaultWords, "version") -> indexOf(defaultWords, targetLabelVersion) // version in prom
                )
              )
          ),
          durations = Map[Int, gDuration](
            indexOf(defaultWords, "response.duration") -> duration // request_duration_[bucket|count|sum] in prom
          )
        )
      )
    )
  }
}

case class MixerClient(service: Service[h2.Request, h2.Response]) {
  import MixerClient._

  private[this] val client = new Mixer.Client(service)

  def report(
    responseCode: Int,
    requestPath: String,
    targetService: String,
    sourceLabelApp: String,
    targetLabelApp: String,
    targetLabelVersion: String,
    duration: Duration
  ): Future[ReportResponse] = {
    val reportRequest =
      mkReportRequest(
        responseCode,
        requestPath,
        targetService,
        sourceLabelApp,
        targetLabelApp,
        targetLabelVersion,
        gDuration(
          seconds = Some(duration.inLongSeconds),
          nanos = Some((duration.inNanoseconds - duration.inLongSeconds * 1000000000L).toInt)
        )
      )
    log.debug(s"MixerClient.report: $reportRequest")
    client.report(reportRequest)
  }
}
