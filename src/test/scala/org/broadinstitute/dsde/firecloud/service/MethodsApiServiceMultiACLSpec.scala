package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockAgoraACLServer
import org.broadinstitute.dsde.firecloud.model.MethodRepository._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impFireCloudPermission, impMethodAclPair}
import org.broadinstitute.dsde.firecloud.webservice.MethodsApiService
import org.broadinstitute.dsde.rawls.model.MethodRepoMethod
import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json._


class MethodsApiServiceMultiACLSpec extends ServiceSpec with MethodsApiService {

  def actorRefFactory = system


  override def beforeAll(): Unit = {
    MockAgoraACLServer.startACLServer()
  }

  override def afterAll(): Unit = {
    MockAgoraACLServer.stopACLServer()
  }

  val localMethodPermissionsPath = s"/$localMethodsPath/permissions"


  // most of the functionality of this endpoint either exists in Agora or is unit-tested elsewhere.
  // here, we just test the routing and basic input/output of the endpoint.

  "Methods Repository multi-ACL upsert endpoint" - {
    "when testing DELETE, GET, POST methods on the multi-permissions path" - {
      // TODO: these methods return NotFound instead of MethodNotAllowed because of passthroughAllPaths on /methods.
      // TODO: when GAWB-1303 is addressed, update this unit test.
      "NotFound is returned" in {
        List(HttpMethods.DELETE, HttpMethods.GET, HttpMethods.POST) map {
          method =>
            new RequestBuilder(method)(localMethodPermissionsPath) ~> sealRoute(routes) ~> check {
              status should equal(NotFound)
            }
        }
      }
    }

    "when sending valid input" - {
      "returns OK and translates responses" in {
        val payload = Seq(
          MethodAclPair(MethodRepoMethod("ns1","n1",1), Seq(FireCloudPermission("user1@example.com","OWNER"))),
          MethodAclPair(MethodRepoMethod("ns2","n2",2), Seq(FireCloudPermission("user2@example.com","READER")))
        )
        Put(localMethodPermissionsPath, payload) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val resp = responseAs[Seq[MethodAclPair]]
          assert(resp.nonEmpty)
        }
      }
    }


    // BAD INPUTS
    "when posting malformed data" - {
      "BadRequest is returned" in {
        // endpoint expects a JsArray; send it a JsObject and expect BadRequest.
        Put(localMethodPermissionsPath, JsObject(Map("foo"->JsString("bar")))) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(BadRequest)
        }
      }
    }

  }
}