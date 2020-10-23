package org.broadinstitute.dsde.firecloud.service

import java.io

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpCharsets, HttpEntity, HttpResponse, MediaTypes, StatusCodes}
import akka.http.scaladsl.server.RequestContext
import akka.pattern.pipe
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.scaladsl.{Source => AkkaSource}

import scala.io.Source
import akka.http.scaladsl.model.HttpEntity.{ChunkStreamPart, Chunked}
import akka.http.scaladsl.model.headers.{Connection, ContentDispositionTypes, `Content-Disposition`}
import akka.http.scaladsl.model.{ContentTypes, HttpResponse}
import akka.util.{ByteString, Timeout}
import better.files.File
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{UserInfo, _}
import org.broadinstitute.dsde.firecloud.service.ExportEntitiesByTypeActor.ExportEntities
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model._
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

case class ExportEntitiesByTypeArguments (
                                           userInfo: UserInfo,
                                           workspaceNamespace: String,
                                           workspaceName: String,
                                           entityType: String,
                                           attributeNames: Option[IndexedSeq[String]],
                                           model: Option[String]
                                         )

object ExportEntitiesByTypeActor {

  sealed trait ExportEntitiesByTypeMessage
  case object ExportEntities extends ExportEntitiesByTypeMessage

  def constructor(app: Application, materializer: ActorMaterializer)(exportArgs: ExportEntitiesByTypeArguments)(implicit executionContext: ExecutionContext) =
    new ExportEntitiesByTypeActor(app.rawlsDAO, exportArgs.userInfo, exportArgs.workspaceNamespace,
      exportArgs.workspaceName, exportArgs.entityType, exportArgs.attributeNames, exportArgs.model, materializer)
}

/**
  * This class takes an akka.stream approach to generating download content. To facilitate sending
  * large amounts of paginated data to the client, we need to process the paginated content with a
  * limited memory footprint. In the case of singular entity downloads, we can also immediately begin
  * a content stream to the user to avoid browser timeouts. In the case of set entity downloads, we
  * can use efficient akka.stream techniques to generate files. Using a paginated approach resolves
  * timeouts between Orchestration and other services. Using akka.streams resolves both memory issues
  * and backpressure considerations between upstream producers and downstream consumers.
  */
class ExportEntitiesByTypeActor(rawlsDAO: RawlsDAO,
                                argUserInfo: UserInfo,
                                workspaceNamespace: String,
                                workspaceName: String,
                                entityType: String,
                                attributeNames: Option[IndexedSeq[String]],
                                model: Option[String],
                                argMaterializer: ActorMaterializer)
                               (implicit protected val executionContext: ExecutionContext) extends LazyLogging {

  implicit val timeout: Timeout = Timeout(1 minute)
  implicit val userInfo: UserInfo = argUserInfo
  implicit val materializer: ActorMaterializer = argMaterializer

  implicit val modelSchema: ModelSchema = model match {
    case Some(name) => ModelSchemaRegistry.getModelForSchemaType(SchemaTypes.withName(name))
    // if no model is specified, use the previous behavior - assume firecloud model
    case None => ModelSchemaRegistry.getModelForSchemaType(SchemaTypes.FIRECLOUD)
  }

  def ExportEntities = streamEntities

  /**
    * Two basic code paths
    *
    * For Collection types, write the content to temp files, zip and return.
    *
    * For Singular types, pipe the content from `Source` -> `Flow` -> `Sink`
    * Source generates the entity queries
    * Flow executes the queries and sends formatted content to chunked response handler
    * Sink finishes the execution pipeline
    *
    * Handle exceptions directly by completing the request.
    */
  def streamEntities(): Future[HttpResponse] = {
    val keepAlive = Connection("Keep-Alive")

    entityTypeMetadata flatMap { metadata =>
      val entityQueries = getEntityQueries(metadata, entityType)
      if (modelSchema.isCollectionType(entityType)) {
        val contentType = ContentTypes.`application/octet-stream`
        val fileName = entityType + ".zip"
        val disposition = `Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> fileName))

        streamCollectionType(entityQueries, metadata).map { source =>
          HttpResponse(entity = Chunked(contentType, source), headers = List(keepAlive, disposition))
        }
      } else {
//        val headers = TSVFormatter.makeEntityHeaders(entityType, metadata.attributeNames, attributeNames)
//
//        val contentType = ContentType(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)
//        val fileName = entityType + ".tsv"
//        val disposition = `Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> fileName))
//
//        streamSingularType(entityQueries, metadata, headers).map { source =>
//          HttpResponse(entity = Chunked(contentType, source), headers = List(keepAlive, disposition))
//        }
        Future.successful(HttpResponse(StatusCodes.NotImplemented))
      }
    }
  }.recoverWith {
    // Standard exceptions have to be handled as a completed request
    case t: Throwable => handleStandardException(t)
  }


  /*
   * Helper Methods
   */

  // Standard exceptions have to be handled as a completed request
  private def handleStandardException(t: Throwable): Future[HttpResponse] = {
    val errorReport = t match {
      case f: FireCloudExceptionWithErrorReport => f.errorReport
      case _ => ErrorReport(StatusCodes.InternalServerError, s"FireCloudException: Error generating entity download: ${t.getMessage}")
    }
    Future(HttpResponse(
      status = errorReport.statusCode.getOrElse(StatusCodes.InternalServerError),
      entity = HttpEntity(ContentTypes.`application/json`, errorReport.toJson.compactPrint)))
  }

  // Stream exceptions have to be handled by directly closing out the RequestContext responder stream
//  private def handleStreamException(t: Throwable): Unit = {
//    logger.info("handling exception", t)
//    val message = t match {
//      case f: FireCloudExceptionWithErrorReport => s"FireCloudException: Error generating entity download: ${f.errorReport.message}"
//      case _ => s"FireCloudException: Error generating entity download: ${t.getMessage}"
//    }
//    ctx.responder ! MessageChunk(message)
//    ctx.responder ! ChunkedMessageEnd
//  }

  /**
    * General Approach
    * 1. Define a `Source` of entity queries
    * 2. Run the source events through a `Flow`.
    * 3. Flow sends events (batch of entities) to a streaming output actor
    * 4. Return a Done to the calling route when complete.
    */
//  private def streamSingularType(entityQueries: Seq[EntityQuery], metadata: EntityTypeMetadata, headers: IndexedSeq[String]): Future[Done] = {
//
//    // The output to the user
//    // The Source
//    val entityQuerySource = AkkaSource(entityQueries.toStream)
//
//    // The Flow. Using mapAsync(1) ensures that we run 1 batch at a time through this side-affecting process.
//    val flow = Flow[EntityQuery].mapAsync(1) { query =>
//      getEntitiesFromQuery(query) map { entities =>
//        sendRowsAsChunks(query, entityQueries.size, headers, entities)
//      }
//    }
//
//    // Ignore the result - we don't need to remember anything about this operation.
//    val sink = Sink.ignore
//
//    // finally, run it:
//    entityQuerySource.via(flow).runWith(sink)
//  }

  private def streamCollectionType(entityQueries: Seq[EntityQuery], metadata: EntityTypeMetadata): Future[AkkaSource[ChunkStreamPart, NotUsed]] = {

    // Two File sinks, one for each kind of entity set file needed.
    // The temp files will end up zipped and streamed when complete.
    val tempEntityFile: File = File.newTemporaryFile(prefix = "entity_")
    val entitySink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(tempEntityFile.path)
    val tempMembershipFile: File = File.newTemporaryFile(prefix = "membership_")
    val membershipSink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(tempMembershipFile.path)

    // Headers
    val entityHeaders: IndexedSeq[String] = TSVFormatter.makeEntityHeaders(entityType, metadata.attributeNames, attributeNames)
    val membershipHeaders: IndexedSeq[String] = TSVFormatter.makeMembershipHeaders(entityType)

    // Run the Split Entity Flow that pipes entities through the two flows to the two file sinks
    // Result of this will be a tuple of Future[IOResult] that represents the success or failure of
    // streaming content to the file sinks.
    val fileStreamIOResults: (Future[IOResult], Future[IOResult]) = {
      RunnableGraph.fromGraph(GraphDSL.create(entitySink, membershipSink)((_, _)) { implicit builder =>
        (eSink, mSink) =>
          import GraphDSL.Implicits._

          // Sources
          val querySource: Outlet[EntityQuery] = builder.add(AkkaSource(entityQueries.toStream)).out
          val entityHeaderSource: Outlet[ByteString] = builder.add(AkkaSource.single(ByteString(entityHeaders.mkString("\t") + "\n"))).out
          val membershipHeaderSource: Outlet[ByteString] = builder.add(AkkaSource.single(ByteString(membershipHeaders.mkString("\t") + "\n"))).out

          // Flows
          val queryFlow: FlowShape[EntityQuery, Seq[Entity]] = builder.add(Flow[EntityQuery].mapAsync(1) { query => getEntitiesFromQuery(query) })
          val splitter: UniformFanOutShape[Seq[Entity], Seq[Entity]] = builder.add(Broadcast[Seq[Entity]](2))
          val entityFlow: FlowShape[Seq[Entity], ByteString] = builder.add(Flow[Seq[Entity]].map { entities =>
            val rows = TSVFormatter.makeEntityRows(entityType, entities, entityHeaders)
            ByteString(rows.map { _.mkString("\t")}.mkString("\n") + "\n")
          })
          val membershipFlow: FlowShape[Seq[Entity], ByteString] = builder.add(Flow[Seq[Entity]].map { entities =>
            val rows = TSVFormatter.makeMembershipRows(entityType, entities)
            ByteString(rows.map { _.mkString("\t")}.mkString("\n") + "\n")
          })
          val eConcat: UniformFanInShape[ByteString, ByteString] = builder.add(Concat[ByteString]())
          val mConcat: UniformFanInShape[ByteString, ByteString] = builder.add(Concat[ByteString]())

          // Graph
          entityHeaderSource                                                 ~> eConcat
          querySource ~>  queryFlow ~> splitter ~> entityFlow     ~> eConcat ~> eSink
          membershipHeaderSource                                             ~> mConcat
          splitter ~> membershipFlow ~> mConcat ~> mSink
          ClosedShape
      }).run()
    }

    // Check that each file is completed
    val fileStreamResult = for {
      eResult <- fileStreamIOResults._1
      mResult <- fileStreamIOResults._2
    } yield eResult.wasSuccessful && mResult.wasSuccessful

    // And then map those files to a ZIP.
    val zipResult = fileStreamResult flatMap { s =>
      if (s) {
        val zipFile: Future[File] = writeFilesToZip(tempEntityFile, tempMembershipFile)
        // The output to the user
        zipFile map { f =>
          val result: AkkaSource[ChunkStreamPart, NotUsed] = AkkaSource.fromIterator(Source.fromFile(f.toJava).getLines).map(x => ChunkStreamPart(x))
          result
        }
      } else {
        Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(s"FireCloudException: Unable to stream zip file to user for $workspaceNamespace:$workspaceName:$entityType")))
      }
    }
    zipResult
  }

//  private def sendRowsAsChunks(query: EntityQuery, querySize: Int, headers: IndexedSeq[String], entities: Seq[Entity]): Unit = {
//    val rows = TSVFormatter.makeEntityRows(entityType, entities, headers)
//    val remaining = querySize - query.page + 1
//    // Send headers as the first chunk of data
//    if (query.page == 1) { actorRef ! FirstChunk(HttpData(headers.mkString("\t") + "\n"), remaining)}
//    // Send entities
//    actorRef ! NextChunk(HttpData(rows.map { _.mkString("\t") }.mkString("\n") + "\n"), remaining - 1)
//  }

  private def writeFilesToZip(entityTSV: File, membershipTSV: File): Future[File] = {
    try {
      val zipFile = File.newTemporaryDirectory()
      membershipTSV.moveTo(zipFile/s"${entityType}_membership.tsv")
      entityTSV.moveTo(zipFile/s"${entityType}_entity.tsv")
      zipFile.zip()
      Future { zipFile.zip() }
    } catch {
      case t: Throwable => Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, s"FireCloudException: Unable to create zip file.", t)))
    }
  }

  private def getEntityQueries(metadata: EntityTypeMetadata, entityType: String): Seq[EntityQuery] = {
    val pageSize = FireCloudConfig.Rawls.defaultPageSize
    val filteredCount = metadata.count
    val sortField = "name" // Anything else and Rawls execution time blows up due to a join (GAWB-2350)
    val pages = Math.ceil(filteredCount.toDouble / pageSize.toDouble).toInt
    (1 to pages) map { page =>
      EntityQuery(page = page, pageSize = pageSize, sortField = sortField, sortDirection = SortDirections.Ascending, filterTerms = None)
    }
  }

  private def entityTypeMetadata: Future[EntityTypeMetadata] = {
    rawlsDAO.getEntityTypes(workspaceNamespace, workspaceName).
      map(_.getOrElse(entityType,
        throw new FireCloudExceptionWithErrorReport(ErrorReport(s"Unable to collect entity metadata for $workspaceNamespace:$workspaceName:$entityType")))
      )
  }

  private def getEntitiesFromQuery(query: EntityQuery): Future[Seq[Entity]] = {
    rawlsDAO.queryEntitiesOfType(workspaceNamespace, workspaceName, entityType, query) map {
      response => response.results
    }
  }

}
