package io.hydrosphere.serving.gateway.persistence.servable

import cats.effect.{Clock, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.application.MonitoringClient
import io.hydrosphere.serving.gateway.execution.grpc.PredictionClient
import io.hydrosphere.serving.gateway.persistence.StoredServable
import io.hydrosphere.serving.gateway.execution.servable.Predictor
import io.hydrosphere.serving.gateway.util.ReadWriteLock

trait ServableStorage[F[_]] {
  def getExecutor(servableName: String): F[Option[Predictor[F]]]
  def getShadowedExecutor(servableName: String): F[Option[Predictor[F]]]

  def get(name: String): F[Option[StoredServable]]

  def list: F[List[StoredServable]]

  def add(apps: Seq[StoredServable]): F[Unit]
  def remove(ids: Seq[String]): F[Unit]
}

object ServableStorage {
  def makeInMemory[F[_]](
    clientCtor: PredictionClient.Factory[F],
    shadow: MonitoringClient[F]
  )(implicit F: Sync[F], clock: Clock[F]) = {
    for {
      lock <- ReadWriteLock.reentrant
    } yield new ServableInMemoryStorage[F](lock, clientCtor, shadow)
  }
}