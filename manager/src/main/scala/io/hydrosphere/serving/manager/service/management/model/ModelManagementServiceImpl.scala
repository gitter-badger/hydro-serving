package io.hydrosphere.serving.manager.service.management.model

import java.time.LocalDateTime

import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.manager.model._
import io.hydrosphere.serving.manager.repository._
import io.hydrosphere.serving.manager.service.contract.description._
import io.hydrosphere.serving.manager.service.contract.ops.TensorProtoOps
import io.hydrosphere.serving.manager.service.contract.{DataGenerator, ModelType}
import io.hydrosphere.serving.manager.service.modelbuild.{ModelBuildService, docker}
import io.hydrosphere.serving.manager.service.modelfetcher.ModelFetcher
import io.hydrosphere.serving.manager.service.modelpush.ModelPushService
import io.hydrosphere.serving.manager.service.modelsource.ModelSource
import org.apache.logging.log4j.scala.Logging
import spray.json.JsObject

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class ModelManagementServiceImpl(
  modelRepository: ModelRepository,
  modelVersionRepository: ModelVersionRepository,
  modelBuildRepository: ModelBuildRepository,
  modelBuildScriptRepository: ModelBuildScriptRepository,
  modelBuildService: ModelBuildService,
  modelPushService: ModelPushService
)(
  implicit val ex: ExecutionContext
) extends ModelManagementService with Logging {

  override def allModels(): Future[Seq[Model]] =
    modelRepository.all()

  override def createModel(entity: CreateOrUpdateModelRequest): Future[Model] =
    modelRepository.create(entity.toModel)

  override def updateModel(entity: CreateOrUpdateModelRequest): Future[Model] = {
    entity.id match {
      case Some(modelId) =>
        modelRepository.get(modelId).flatMap {
          case Some(foundModel) =>
            val newModel = entity.toModel(foundModel)
            modelRepository
              .update(newModel)
              .map(_ => newModel)
          case None => throw new IllegalArgumentException(s"Can't find Model with id ${entity.id.get}")
        }
      case None => throw new IllegalArgumentException("Id required for this action")
    }
  }

  override def addModelVersion(entity: CreateModelVersionRequest): Future[ModelVersion] =
    fetchModel(entity.modelId).flatMap(model => {
      modelVersionRepository.create(entity.toModelVersion(model))
    })

  override def allModelVersion(): Future[Seq[ModelVersion]] =
    modelVersionRepository.all()

  override def lastModelVersionByModelId(id: Long, maximum: Int): Future[Seq[ModelVersion]] =
    modelVersionRepository.lastModelVersionByModel(id: Long, maximum: Int)

  override def updatedInModelSource(entity: Model): Future[Unit] = {
    modelRepository.fetchBySource(entity.source)
      .flatMap {
        case Nil =>
          addModel(entity).map(_ => Unit)
        case _ => modelRepository.updateLastUpdatedTime(entity.source, LocalDateTime.now())
          .map(_ => Unit)
      }

  }

  override def modelBuildsByModelId(id: Long): Future[Seq[ModelBuild]] =
    modelBuildRepository.listByModelId(id)

  override def lastModelBuildsByModelId(id: Long, maximum: Int): Future[Seq[ModelBuild]] =
    modelBuildRepository.lastByModelId(id, maximum)

  private def buildNewModelVersion(model: Model, modelVersion: Option[Long]): Future[ModelVersion] = {
    fetchLastModelVersion(model.id, modelVersion).flatMap { version =>
      fetchScriptForModel(model).flatMap { script =>
        modelBuildRepository.create(
          ModelBuild(
            id = 0,
            model = model,
            version = version,
            started = LocalDateTime.now(),
            finished = None,
            status = ModelBuildStatus.STARTED,
            statusText = None,
            logsUrl = None,
            modelVersion = None
          )).flatMap { modelBuild =>
          buildModelRuntime(modelBuild, script).transform(
            runtime => {
              modelBuildRepository.finishBuild(modelBuild.id, ModelBuildStatus.FINISHED, "OK", LocalDateTime.now(), Some(runtime))
              runtime
            },
            ex => {
              logger.error(ex.getMessage, ex)
              modelBuildRepository.finishBuild(modelBuild.id, ModelBuildStatus.ERROR, ex.getMessage, LocalDateTime.now(), None)
              ex
            }
          )
        }
      }
    }
  }

  override def buildModel(modelId: Long, modelVersion: Option[Long]): Future[ModelVersion] =
    modelRepository.get(modelId)
      .flatMap {
        case None => throw new IllegalArgumentException(s"Can't find Model with id $modelId")
        case Some(model) =>
          buildNewModelVersion(model, modelVersion)
      }

  private def fetchScriptForModel(model: Model): Future[String] =
    modelBuildScriptRepository.get(model.name).flatMap {
      case Some(script) => Future.successful(script.script)
      case None => Future.successful(
        """FROM busybox:1.28.0
           LABEL MODEL_TYPE={MODEL_TYPE}
           LABEL MODEL_NAME={MODEL_NAME}
           LABEL MODEL_VERSION={MODEL_VERSION}
           VOLUME /model
           ADD {MODEL_PATH} /model""")
    }

  private def buildModelRuntime(modelBuild: ModelBuild, script: String): Future[ModelVersion] = {
    val handler = new docker.ProgressHandler.LoggerHandler(logger)

    val imageName = modelPushService.getImageName(modelBuild)
    modelBuildService.build(modelBuild, imageName, script, handler).flatMap { sha256 =>
      modelVersionRepository.create(ModelVersion(
        id = 0,
        imageName = imageName,
        imageTag = modelBuild.version.toString,
        imageSHA256 = sha256,
        modelName = modelBuild.model.name,
        modelVersion = modelBuild.version,
        source = Some(modelBuild.model.source),
        modelContract = modelBuild.model.modelContract,
        created = LocalDateTime.now,
        model = Some(modelBuild.model),
        modelType = modelBuild.model.modelType
      )).flatMap { modelRuntime =>
        Future(modelPushService.push(modelRuntime, handler)).map(_ => modelRuntime)
      }
    }
  }

  private def fetchLastModelVersion(modelId: Long, modelVersion: Option[Long]): Future[Long] = {
    modelVersion match {
      case Some(x) => modelVersionRepository.modelVersionByModelAndVersion(modelId, x).map {
        case None => x
        case _ => throw new IllegalArgumentException("You already have such version")
      }
      case _ => modelVersionRepository.lastModelVersionByModel(modelId, 1)
        .map {
          _.headOption match {
            case None => 1
            case Some(runtime) => runtime.modelVersion + 1
          }
        }
    }
  }

  private def fetchModel(id: Option[Long]): Future[Option[Model]] = {
    if (id.isEmpty) {
      Future.successful(None)
    } else {
      modelRepository
        .get(id.get)
        .map {
          _.orElse(throw new IllegalArgumentException(s"Can't find Model with id ${id.get}"))
        }
    }
  }

  private def addModel(model: Model): Future[Model] = {
    modelRepository.create(model)
  }

  def deleteModel(modelName: String): Future[Model] = {
    modelRepository.get(modelName).flatMap {
      case Some(model) =>
        modelRepository.delete(model.id)
        Future.successful(model)
      case None =>
        Future.failed(new NoSuchElementException(s"$modelName model"))
    }
  }

  override def updateModel(modelName: String, modelSource: ModelSource): Future[Option[Model]] = {
    if (modelSource.isExist(modelName)) {
      // model is updated
      val modelMetadata = ModelFetcher.getModel(modelSource, modelName)
      modelRepository.get(modelMetadata.modelName).flatMap {
        case Some(oldModel) =>
          val newModel = Model(
            id = oldModel.id,
            name = modelMetadata.modelName,
            source = s"${modelSource.sourceDef.prefix}:${modelMetadata.modelName}",
            modelType = modelMetadata.modelType,
            description = None,
            modelContract = modelMetadata.contract,
            created = oldModel.created,
            updated = LocalDateTime.now()
          )
          modelRepository.update(newModel).map(_ => Some(newModel))
        case None =>
          val newModel = Model(
            id = -1,
            name = modelMetadata.modelName,
            source = s"${modelSource.sourceDef.prefix}:${modelMetadata.modelName}",
            modelType = modelMetadata.modelType,
            description = None,
            modelContract = modelMetadata.contract,
            created = LocalDateTime.now(),
            updated = LocalDateTime.now()
          )
          modelRepository.create(newModel).map(x => Some(x))
      }
    } else {
      // model is deleted
      modelRepository.get(modelName).map { opt =>
        opt.map { model =>
          modelRepository.delete(model.id)
          model
        }
      }
    }
  }

  override def generateModelPayload(modelId: Long, signature: String): Future[Seq[JsObject]] =
    modelRepository.get(modelId).map {
      case None => throw new IllegalArgumentException(s"Can't find model modelId=$modelId")
      case Some(model) =>
        generatePayload(model.modelContract, signature)
    }

  private def generatePayload(contract: ModelContract, signature: String): Seq[JsObject] = {
    val res = DataGenerator.forContract(contract, signature)
      .getOrElse(throw new IllegalArgumentException(s"Can't find signature model signature=$signature"))
    Seq(TensorProtoOps.jsonify(res.generateInputs))
  }


  override def generateInputsForVersion(versionId: Long, signature: String): Future[Seq[JsObject]] =
    modelVersionRepository.get(versionId).map {
      case None => throw new IllegalArgumentException(s"Can't find model version id=$versionId")
      case Some(version) =>
        generatePayload(version.modelContract, signature)
    }

  override def submitContract(modelId: Long, prototext: String): Future[Option[Model]] = {
    ModelContract.validateAscii(prototext) match {
      case Left(a) => Future.failed(new IllegalArgumentException(a.msg))
      case Right(b) => updateModelContract(modelId, b)
    }
  }

  override def submitFlatContract(
    modelId: Long,
    contractDescription: ContractDescription
  ): Future[Option[Model]] = {
    val contract = contractDescription.toContract // TODO Error handling
    updateModelContract(modelId, contract)
  }

  override def submitBinaryContract(modelId: Long, bytes: Array[Byte]): Future[Option[Model]] = {
    ModelContract.validate(bytes) match {
      case Failure(exception) => Future.failed(exception)
      case Success(value) => updateModelContract(modelId, value)
    }
  }

  private def updateModelContract(modelId: Long, modelContract: ModelContract): Future[Option[Model]] = {
    modelRepository.get(modelId).flatMap {
      case Some(model) =>
        val newModel = model.copy(modelContract = modelContract) // TODO contract validation (?)
        modelRepository.update(newModel).map { _ => Some(newModel) }
      case None => Future.successful(None)
    }
  }

  override def modelsByType(types: Set[String]): Future[Seq[Model]] =
    modelRepository.fetchByModelType(types.map(ModelType.fromTag).toSeq)
}
