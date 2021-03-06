package io.hydrosphere.serving.gateway.api.grpc

import cats.effect.Effect
import cats.effect.syntax.effect._
import cats.implicits._
import com.google.protobuf.empty.Empty
import io.hydrosphere.serving.gateway.execution.ExecutionService
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc.PredictionService
import io.hydrosphere.serving.tensorflow.api.prediction_service.StatusResponse
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.Future

class PredictionServiceEndpoint[F[_]: Effect](
  executor: ExecutionService[F]
) extends PredictionService with Logging {

  override def predict(request: PredictRequest): Future[PredictResponse] = {
    logger.info(s"Got request from GRPC. modelSpec=${request.modelSpec}")
    Effect[F].attempt(executor.predict(request))
      .map {
        case Right(result) =>
          logger.info("Returning successful GRPC response")
          result.asRight
        case Left(error) =>
          logger.warn("Returning failed GRPC response", error)
          error.asLeft
      }
      .rethrow.toIO.unsafeToFuture()
  }

  override def status(request: Empty): Future[StatusResponse] = Future.successful(
    StatusResponse(
      status = StatusResponse.ServiceStatus.SERVING,
      message = "I'm ready"
    )
  )
}