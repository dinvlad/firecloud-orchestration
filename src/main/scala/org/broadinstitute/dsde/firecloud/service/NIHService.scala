package org.broadinstitute.dsde.firecloud.service

import akka.actor.Props
import authentikat.jwt._
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.{ProfileClient, ProfileClientActor}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.routing._

import spray.httpx.unmarshalling._

trait NIHService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  val routes: Route =
    requireUserInfo() { userInfo =>
      // api/nih/callback: accept JWT, update linkage + lastlogin
      path("nih" / "callback") {
        post {
          entity(as[JWTWrapper]) { jwtWrapper => requestContext =>

            // get the token from the json wrapper
            val jwt = jwtWrapper.jwt

            // validate the token
            val isValid = JsonWebToken.validate(jwt, FireCloudConfig.Shibboleth.signingKey)

            if (!isValid) {
              requestContext.complete(StatusCodes.BadRequest)
            } else {
              // the NIH JWT is nonstandard. The claims portion of the token *should* be json, but is in fact
              // a simple string. So, libraries tend to fail when working with it. Extract it manually.
              // we shouldn't have to check for bad/missing parts of the token, because we've already validated it.
              val claim = jwt.split("\\.")(1)

              // decode it
              val decoded = java.util.Base64.getDecoder.decode(claim)

              // the entirety of the claims portion of the jwt is the NIH username.
              val linkedNihUsername = new String(decoded)
              // JWT standard uses epoch time for dates, so we'll follow that convention here.
              val lastLinkTime = System.currentTimeMillis() / 1000L;
              // TODO: look up in whitelist, once that functionality exists!
              val isDbgapAuthorized = false

              val nihLink = NIHLink(linkedNihUsername, lastLinkTime, isDbgapAuthorized)

              // TODO: call API to update proxy groups, once it exists

              // save the NIH link keys into Thurloe
              perRequest(requestContext, Props(new ProfileClientActor(requestContext)),
                ProfileClient.UpdateNIHLink(userInfo, nihLink))
            }
          }
        }
      }
    }
}