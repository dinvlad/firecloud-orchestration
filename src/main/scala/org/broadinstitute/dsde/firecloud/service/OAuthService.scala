package org.broadinstitute.dsde.firecloud.service

import akka.actor.Props
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.{ProfileClient, ProfileClientActor}
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{UserInfo, RawlsTokenDate, RawlsToken, OAuthException}
import org.joda.time.{Days, DateTime}
import org.slf4j.LoggerFactory
import spray.http.Uri._
import spray.http.{HttpResponse, OAuth2BearerToken, StatusCodes, Uri}
import spray.routing._
import spray.client.pipelining._
import spray.httpx.SprayJsonSupport._

import scala.concurrent.Future
import scala.util.{Failure, Success}

// see https://developers.google.com/identity/protocols/OAuth2WebServer

object OAuthService {
  val remoteTokenPutPath = FireCloudConfig.Rawls.authPrefix + "/user/refreshToken"
  val remoteTokenPutUrl = FireCloudConfig.Rawls.baseUrl + remoteTokenPutPath

  val remoteTokenDatePath = FireCloudConfig.Rawls.authPrefix + "/user/refreshTokenDate"
  val remoteTokenDateUrl = FireCloudConfig.Rawls.baseUrl + remoteTokenDatePath

}

trait OAuthService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  val routes: Route =
    path("login") {
      get {
        parameters("path".?, "prompt".?, "callback".?) { (userpath, prompt, callback) => requestContext =>

          /*
           * prompt:    either "force" or "auto". Anything else will be treated as "auto".
           *              "force" always requests a new refresh token, prompting the user for offline permission.
           *              "auto" allows Google to determine if it thinks the user needs a new refresh token,
           *                which is not necessarily in sync with rawls' cache.
           *
           * callback:  once OAuth is done and we have an access token from Google, to what hostname should we
           *              return the browser? Example: "https://portal.firecloud.org/"
           * path:      once OAuth is done and we have an access token from Google, to what fragment should we
           *              return the browser? Example: "workspaces"
           *
           * The FireCloud UI typically calls /login with a callback parameter containing the hostname of the UI.
           *  It does not typically pass a path parameter.
           *  It never passes a prompt parameter; this exists to allow developers to call /login manually to
           *    get a new refresh token.
           */

          // create a hash-delimited string of the callback+path and pass into the state param.
          // the state param is defined by and required by the OAuth standard, and we override its typical
          // usage here to pass the UI's hostname/fragment through the OAuth dance.
          // TODO: future story: generate and persist unique security token along with the callback/path
          val state = callback.getOrElse("") + "#" + userpath.getOrElse("")

          // if the user requested "force" then "force"; if the user specified something else, or nothing, use "auto".
          // this allows the end user/UI to control when we force-request a new refresh token. Default to auto,
          // to allow Google to decide; we'll verify in our token store after this step completes.
          val approvalPrompt = prompt match {
            case Some("force") => "force"
            case _ => "auto"
          }

          try {
            // Create the authentication url and redirect the browser. This will redirect to Google, who displays
            // the login screen. Once the user has logged in, Google will redirect to the /callback endpoint,
            // just below here in this class.
            // Do not confuse the /callback endpoint with the callback parameter!
            initiateAuth(state, approvalPrompt=approvalPrompt, requestContext)
          } catch {
            /* we don't expect any exceptions here; if we get any, it's likely
                a misconfiguration of our client id/secrets/callback. But since this is about
                authentication, we are extra careful.
             */
            case e: Exception =>
              log.error("problem during OAuth redirect", e)
              requestContext.complete(StatusCodes.Unauthorized, e.getMessage)
          }
        }
      }
    } ~
    path("callback") {
      get {
        /*
          interpret auth server's response
            An error response has "?error=access_denied"
            An authorization code response has "?code=4/P7q7W91a-oMsCeLvIaQm6bTrgtp7"
        */
        parameters("code", "state") { (code, actualState) => requestContext =>

          /*
           * code:  the temporary authentication code from Google that we will use to generate access/refresh tokens
           * state: the FireCloud UI's hostname+fragment to which we will eventually redirect once the OAuth dance
           *         is done. See the /login endpoint above here in this class.
           */

          // TODO: future story: check the security token we previously persisted in the expected-state
          val expectedState = actualState

          try {
            // is it worth breaking this out into an async/perrequest actor, so we don't block a thread?
            // not sure what the google library does under the covers - is it blocking?
            val gcsTokenResponse = HttpGoogleServicesDAO.getTokens(actualState, expectedState, code)
            val accessToken = gcsTokenResponse.access_token
            val refreshToken = gcsTokenResponse.refresh_token

            // update the NIH link expiration time, as applicable
            gcsTokenResponse.subject_id match {
              case Some(sub) =>
                // NB: we use dummy values in the UserInfo object for email addr and expire time; these are irrelevant
                val userInfo:UserInfo = UserInfo("", OAuth2BearerToken(accessToken), -1, sub)
                perRequest(requestContext, Props(new ProfileClientActor(requestContext)),
                  ProfileClient.GetAndUpdateNIHStatus(userInfo))
              case None =>
                log.warn("Could not determine current user's subjectID; NIH link expiration will not be updated")
            }

            // we can't use the standard externalHttpPerRequest here, because the requestContext doesn't have the
            // access token yet; we have to add the token manually
            val pipeline = addCredentials(OAuth2BearerToken(accessToken)) ~> sendReceive

            // if we have a refresh token, store it in rawls
            refreshToken match {
              case Some(rt) =>
                val tokenReq = Put(OAuthService.remoteTokenPutUrl, RawlsToken(rt))
                val tokenStoreFuture: Future[HttpResponse] = pipeline { tokenReq }

                // we intentionally don't gate the login process on storage of the refresh token. Token storage
                // will happen async. If token storage fails, we rely on underlying services to notice the
                // missing token and re-initiate the oauth grants.
                tokenStoreFuture onComplete {
                  case Success(response) =>
                    response.status match {
                      case StatusCodes.Created => log.info("successfully stored refresh token")
                      case x => log.warn(s"failed to store refresh token (status code $x): " + response.entity)
                    }
                  case Failure(error) => log.warn("failed to store refresh token: " + error.getMessage)
                }
                completeAuthWithRedirect(actualState, accessToken, requestContext)
              case None =>
                val tokenDateReq = Get(OAuthService.remoteTokenDateUrl)
                val tokenDateFuture: Future[HttpResponse] = pipeline { tokenDateReq }

                tokenDateFuture onComplete {
                  case Success(response) =>
                    response.status match {
                      case StatusCodes.OK =>
                        // rawls found a refresh token; check its date
                        val tokenDate = unmarshal[RawlsTokenDate].apply(response)
                        val howOld = Days.daysBetween(DateTime.parse(tokenDate.refreshTokenUpdatedDate), DateTime.now)
                        howOld.getDays match {
                          case x if x < 90 =>
                            log.debug(s"User's refresh token is $x days old; all good!")
                            completeAuthWithRedirect(actualState, accessToken, requestContext) // fine; continue on
                          case x =>
                            log.info(s"User's refresh token is $x days old; requesting a new one.")
                            initiateAuth(actualState, "force", requestContext)
                        }
                      case StatusCodes.NotFound =>
                        log.info(s"User does not already have a refresh token; requesting a new one.")
                        // rawls does not have a refresh token for us. restart auth.
                        // TODO: if the rawls put-token endpoint goes down, this will cause an infinite loop in login
                        initiateAuth(actualState, "force", requestContext)
                      case x =>
                        log.warn("Unexpected response code when querying rawls for existence of refresh token: "
                          + x.value + " " + x.reason)
                        completeAuthWithRedirect(actualState, accessToken, requestContext)
                    }
                  case Failure(error) =>
                    log.warn("Could not query rawls for existence of refresh token: " + error.getMessage)
                    completeAuthWithRedirect(actualState, accessToken, requestContext)
                }
            }
          } catch {
            case e:OAuthException => complete(StatusCodes.Unauthorized, e.getMessage) // these can be shown to the user
            case e: Exception => {
              log.error("problem during OAuth code exchange", e)
              requestContext.complete(StatusCodes.Unauthorized, e.getMessage)
            }
          }
        } ~
          parameter("error") { errorMsg =>
            // echo the oauth error back to the user. Is that safe to do?
            complete(StatusCodes.Unauthorized, errorMsg)
          }
      }
    }


  private def initiateAuth(state: String, approvalPrompt: String, requestContext: RequestContext) =  {
    val gcsAuthUrl = HttpGoogleServicesDAO.getGoogleRedirectURI(state, approvalPrompt=approvalPrompt)
    requestContext.redirect(gcsAuthUrl, StatusCodes.TemporaryRedirect)
  }

  private def completeAuthWithRedirect(actualState: String, accessToken: String, requestContext: RequestContext) = {
    // as created in the /login endpoint, the actual state contains the desired callback hostname
    // and the desired callback fragment, separated by a hash. But, either value may be the empty string.
    // the hostname+fragment contained in actualState will typically be the FireCloud UI's url.

    val redirectParts = actualState.split("#")
    val List(redirectBase, redirectFragment) = redirectParts match {
      // callback#fragment
      case x if x.length == 2 => List(redirectParts(0), redirectParts(1))
      // #fragment
      case x if (x.length == 1 && actualState.startsWith("#")) => List("", redirectParts(0))
      // callback#
      case x if (x.length == 1 && actualState.endsWith("#")) => List(redirectParts(0), "")
      // #
      case x if x.length == 0 => List("", "")
      // an unexpected case where we have too many parts. Do our best to handle this gracefully.
      case _ =>
        log.debug(s"Unexpected state parameter during login callback: [$actualState]")
        List(redirectParts.head, redirectParts.tail.mkString("#"))
    }

    // validate the redirect base against our known whitelist.
    // this will return the original value if it exists in the whitelist,
    // or the empty string if it does not
    val finalRedirectBase = HttpGoogleServicesDAO.whitelistRedirect(redirectBase)

    // redirect to the root url ("/"), with a fragment containing the user's original path and
    // the access token. The access token part LOOKS like a query param, but the entire string including "?"
    // is actually the fragment and the UI will parse it out.
    val uiRedirect = Uri(finalRedirectBase)
      .withPath(Uri.Path./)
      .withFragment(s"$redirectFragment?access_token=$accessToken")

    requestContext.redirect(Uri(uiRedirect.toString), StatusCodes.TemporaryRedirect)
  }



}
