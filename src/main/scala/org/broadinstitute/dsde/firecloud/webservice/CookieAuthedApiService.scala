package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.{ActorRefFactory, Props}
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.{Directives, Route}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service._

import scala.concurrent.ExecutionContext
import scala.language.postfixOps

/**
  * Created by dvoet on 11/16/16.
  */
trait CookieAuthedApiService extends Directives with RequestBuilding with LazyLogging {

  val exportEntitiesByTypeConstructor: ExportEntitiesByTypeArguments => ExportEntitiesByTypeActor

  implicit val executionContext: ExecutionContext

  val storageServiceConstructor: UserInfo => StorageService

  private def dummyUserInfo(tokenStr: String) = UserInfo("dummy", OAuth2BearerToken(tokenStr), -1, "dummy")

  val cookieAuthedRoutes: Route =
  // download "proxies" for TSV files
  // Note that these endpoints work in the same way as ExportEntitiesApiService tsv download.
    path( "cookie-authed" / "workspaces" / Segment / Segment/ "entities" / Segment / "tsv" ) { (workspaceNamespace, workspaceName, entityType) =>
      // this endpoint allows an arbitrary number of attribute names in the POST body (GAWB-1435)
      // but the URL cannot be saved for later use (firecloud-app#80)
      post {
        formFields('FCtoken, 'attributeNames.?, 'model.?) { (tokenValue, attributeNamesString, modelString) =>
          val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
          val userInfo = dummyUserInfo(tokenValue)
          val exportArgs = ExportEntitiesByTypeArguments(userInfo, workspaceNamespace, workspaceName, entityType, attributeNames, modelString)

          complete { exportEntitiesByTypeConstructor(exportArgs).ExportEntities }
        }
      } ~
        // this endpoint allows saving the URL for later use (firecloud-app#80)
        // but it's possible to exceed the maximum URI length by specifying too many attributes (GAWB-1435)
        get {
          cookie("FCtoken") { tokenCookie =>
            parameters('attributeNames.?, 'model.?) { (attributeNamesString, modelString) =>
              val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
              val userInfo = dummyUserInfo(tokenCookie.value)
              val exportArgs = ExportEntitiesByTypeArguments(userInfo, workspaceNamespace, workspaceName, entityType, attributeNames, modelString)

              complete { exportEntitiesByTypeConstructor(exportArgs).ExportEntities }
            }
          }
        }
    } ~
      path( "cookie-authed" / "download" / "b" / Segment / "o" / RemainingPath ) { (bucket, obj) =>
        get {
          cookie("FCtoken") { tokenCookie =>
            val userInfo = dummyUserInfo(tokenCookie.value)

            complete { storageServiceConstructor(userInfo).GetDownload(bucket, obj.toString) }
          }
        }
      }

}
