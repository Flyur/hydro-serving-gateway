package io.hydrosphere.serving.gateway

import java.util.concurrent.TimeUnit

import cats.implicits._
import io.grpc.{Channel, ClientInterceptors, ManagedChannelBuilder}
import io.hydrosphere.serving.gateway.discovery.application.XDSApplicationUpdateService
import io.hydrosphere.serving.gateway.grpc.GrpcApi
import io.hydrosphere.serving.gateway.http.HttpApi
import io.hydrosphere.serving.gateway.service.application.ApplicationExecutionServiceImpl
import io.hydrosphere.serving.gateway.persistence.application.ApplicationInMemoryStorage
import io.hydrosphere.serving.grpc.{AuthorityReplacerInterceptor, Headers}
import io.hydrosphere.serving.monitoring.monitoring.MonitoringServiceGrpc
import io.hydrosphere.serving.profiler.profiler.DataProfilerServiceGrpc
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration


object Main extends App with Logging {
  logger.info("Hydroserving gateway service")
  try {

    import io.hydrosphere.serving.gateway.config.Inject._

    implicit val ec: ExecutionContext = ExecutionContext.global

    logger.debug(s"Setting up GRPC sidecar channel")
    val builder = ManagedChannelBuilder
      .forAddress(appConfig.sidecar.host, appConfig.sidecar.port)
    builder.enableRetry()
    builder.usePlaintext()

    val sidecarChannel: Channel = ClientInterceptors
      .intercept(builder.build, new AuthorityReplacerInterceptor +: Headers.interceptors: _*)

    val predictGrpcClient = PredictionServiceGrpc.stub(sidecarChannel)
    val profilerGrpcClient = DataProfilerServiceGrpc.stub(sidecarChannel)
    val monitoringGrpcClient = MonitoringServiceGrpc.stub(sidecarChannel)

    logger.debug(s"Initializing application storage")
    val applicationStorage = new ApplicationInMemoryStorage[Future]()

    logger.debug(s"Initializing application update service")
    val applicationUpdater = new XDSApplicationUpdateService(applicationStorage, appConfig.sidecar)

    logger.debug("Initializing app execution service")
    val gatewayPredictionService = new ApplicationExecutionServiceImpl(
      appConfig.application,
      applicationStorage,
      predictGrpcClient,
      profilerGrpcClient,
      monitoringGrpcClient
    )

    val grpcApi = new GrpcApi(appConfig.application, gatewayPredictionService)

    val httpApi = new HttpApi(appConfig.application, gatewayPredictionService)

    sys addShutdownHook {
      logger.info("Terminating actor system")
      actorSystem.terminate()
      logger.info("Shutting down server")
      grpcApi.server.shutdown()
      try {
        grpcApi.server.awaitTermination(30, TimeUnit.SECONDS)
        Await.ready(httpApi.system.whenTerminated, Duration(30, TimeUnit.SECONDS))
      } catch {
        case e: Throwable =>
          logger.error("Error on terminate", e)
          sys.exit(1)
      }
    }
    logger.info("Initialization completed")
  } catch {
    case e: Throwable =>
      logger.error("Fatal error", e)
      sys.exit(1)
  }
}
