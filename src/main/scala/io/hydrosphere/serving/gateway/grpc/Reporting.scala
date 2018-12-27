package io.hydrosphere.serving.gateway.grpc

import cats.{Applicative, Functor, Monad}
import cats.data.NonEmptyList
import cats.effect.{Async, IO, LiftIO}
import cats.syntax.functor._
import cats.syntax.flatMap._
import io.grpc.Channel
import io.hydrosphere.serving.gateway.config.{ApplicationConfig, Configuration, HttpServiceAddr}
import io.hydrosphere.serving.gateway.grpc.reqstore.{Destination, ReqStore}
import io.hydrosphere.serving.gateway.service.application.ExecutionUnit
import io.hydrosphere.serving.grpc.AuthorityReplacerInterceptor
import io.hydrosphere.serving.monitoring.monitoring.{ExecutionInformation, ExecutionMetadata, MonitoringServiceGrpc, TraceData}
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.monitoring.MonitoringServiceGrpc.MonitoringServiceStub
import io.hydrosphere.serving.profiler.profiler.DataProfilerServiceGrpc
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest

import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait Reporter[F[_]] {
  def send(execInfo: ExecutionInformation): F[Unit]
}

object Reporter {

  def apply[F[_]](f: ExecutionInformation => F[Unit]): Reporter[F] =
    new Reporter[F] {
      def send(execInfo: ExecutionInformation): F[Unit] = f(execInfo)
    }

  def fromFuture[F[_]: Functor, A](f: ExecutionInformation => Future[A])(implicit F: LiftIO[F]): Reporter[F] =
    Reporter(info => F.liftIO(IO.fromFuture(IO(f(info)))).void)


  def profilingGrpc[F[_]: Functor: LiftIO](
    deadline: Duration,
    destination: String,
    grpcClient: DataProfilerServiceGrpc.DataProfilerServiceStub
  ): Reporter[F] = {
    Reporter.fromFuture(info => {
      grpcClient
        .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, destination)
        .withDeadlineAfter(deadline.length, deadline.unit)
        .analyze(info)
    })
  }

}

object Reporters {

  object Monitoring {

    def envoyBased[F[_]: Functor: LiftIO](channel: Channel, appConf: ApplicationConfig): Reporter[F] = {
      val stub = MonitoringServiceGrpc.stub(channel)
      monitoringGrpc(appConf.grpc.deadline, appConf.monitoringDestination, stub)
    }

    def monitoringGrpc[F[_] : Functor : LiftIO](
      deadline: Duration,
      destination: String,
      grpcClient: MonitoringServiceStub
    ): Reporter[F] = {
      Reporter.fromFuture(info => {
        grpcClient
          .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, destination)
          .withDeadlineAfter(deadline.length, deadline.unit)
          .analyze(info)
      })
    }

  }

  object Profiling {

    def envoyBased[F[_]: Functor: LiftIO](channel: Channel, appConf: ApplicationConfig): Reporter[F] = {
      val stub = DataProfilerServiceGrpc.stub(channel)
      profilingGrpc(appConf.grpc.deadline, appConf.profilingDestination, stub)
    }

    def profilingGrpc[F[_]: Functor: LiftIO](
      deadline: Duration,
      destination: String,
      grpcClient: DataProfilerServiceGrpc.DataProfilerServiceStub
    ): Reporter[F] = {
      Reporter.fromFuture(info => {
        grpcClient
          .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, destination)
          .withDeadlineAfter(deadline.length, deadline.unit)
          .analyze(info)
      })
    }
  }
}


trait Reporting[F[_]] {
  def report(request: PredictRequest, eu: ExecutionUnit, value: ResponseOrError): F[Unit]
}

object Reporting {

  type MKInfo[F[_]] = (PredictRequest, ExecutionUnit, ResponseOrError) => F[ExecutionInformation]

  def default[F[_]: Async](channel: Channel, conf: Configuration): Reporting[F] = {
    val appConf = conf.application
    val monitoring = Reporters.Monitoring.envoyBased(channel, appConf)
    val dataProfiler = Reporters.Profiling.envoyBased(channel, appConf)

    val mkInfo =
      if (appConf.reqstore.enabled) {
      val destination = appConf.reqstore.address match {
        case HttpServiceAddr.EnvoyRoute(name) =>
          Destination.EnvoyRoute(conf.sidecar.host, conf.sidecar.port, name, "http")
        case HttpServiceAddr.RealAddress(host, port, schema) =>
          Destination.HostPort(host, port, schema)
      }
      val reqStore = ReqStore.create[F, (PredictRequest, ResponseOrError)](destination)
    } else 

    create0(NonEmptyList.of(monitoring, dataProfiler))
  }

  // todo ContextShift + special ExecutionContext
  def create0[F[_]: Monad](
    mkInfo: (PredictRequest, ExecutionUnit, ResponseOrError) => F[ExecutionInformation],
    reporters: NonEmptyList[Reporter[F]]
  ): Reporting[F] = {
    new Reporting[F] {
      def report(
        request: PredictRequest,
        eu: ExecutionUnit,
        value: ResponseOrError
      ): F[Unit] = {
        mkInfo(request, eu, value).flatMap(info => {
          reporters.traverse(r => r.send(info)).void
        })
      }
    }
  }

  private def mkExecutionInformation(
    request: PredictRequest,
    eu: ExecutionUnit,
    value: ResponseOrError,
    traceData: Option[TraceData]
  ): ExecutionInformation = {
    ExecutionInformation(
      metadata = Option(ExecutionMetadata(
        applicationId = eu.stageInfo.applicationId,
        stageId = eu.stageInfo.stageId,
        modelVersionId = eu.stageInfo.modelVersionId.getOrElse(-1),
        signatureName = eu.stageInfo.signatureName,
        applicationRequestId = eu.stageInfo.applicationRequestId.getOrElse(""),
        requestId = eu.stageInfo.applicationRequestId.getOrElse(""), //todo fetch from response,
        applicationNamespace = eu.stageInfo.applicationNamespace.getOrElse(""),
        dataTypes = eu.stageInfo.dataProfileFields,
        traceData = traceData
      )),
      request = Option(request),
      responseOrError = value
    )
  }

  def noop[F[_]](implicit F: Applicative[F]): Reporting[F] = (_, _, _) => F.pure(())
}
