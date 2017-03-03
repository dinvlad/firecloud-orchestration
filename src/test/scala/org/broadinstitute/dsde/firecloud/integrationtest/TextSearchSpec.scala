package org.broadinstitute.dsde.firecloud.integrationtest

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.broadinstitute.dsde.firecloud.model.LibrarySearchResponse
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json.JsValue

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class TextSearchSpec extends FreeSpec with Matchers with BeforeAndAfterAll with LazyLogging {

  val dur = Duration(2, MINUTES)


  override def beforeAll = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
    // make sure we specify refresh=true here; otherwise, the documents may not be available in the index by the
    // time the tests start, leading to test failures.
    logger.info("indexing fixtures ...")
    searchDAO.bulkIndex(IntegrationTestFixtures.fixtureDocs, refresh = true)
    logger.info("... fixtures indexed.")
  }

  override def afterAll = {
    searchDAO.deleteIndex()
  }

  "Library integration" - {
    "Elastic Search" - {
      "Index exists" in {
        assert(searchDAO.indexExists())
      }
    }
    "search for 'brca'" - {
      "should find just the two BRCA datasets" in {
        val searchResponse = searchFor("brca")
        assertResult(2) {searchResponse.total}
        validateResultNames(
          Set("TCGA_BRCA_ControlledAccess", "TCGA_BRCA_OpenAccess"),
          searchResponse
        )
      }
    }
    "search for 'tcga_brca'" - {
      "should find just the two BRCA datasets" in {
        val searchResponse = searchFor("tcga_brca")
        assertResult(2) {searchResponse.total}
        validateResultNames(
          Set("TCGA_BRCA_ControlledAccess", "TCGA_BRCA_OpenAccess"),
          searchResponse
        )
      }
    }
    "search for 'tcga brca'" - {
      "should find just the two BRCA datasets" in {
        val searchResponse = searchFor("tcga brca")
        assertResult(2) {searchResponse.total}
        validateResultNames(
          Set("TCGA_BRCA_ControlledAccess", "TCGA_BRCA_OpenAccess"),
          searchResponse
        )
      }
    }
    "search for 'tcga_brca_openaccess'" - {
      "should find just the single BRCA open-access dataset" in {
        val searchResponse = searchFor("tcga_brca_openaccess")
        assertResult(1) {searchResponse.total}
        validateResultNames(
          Set("TCGA_BRCA_OpenAccess"),
          searchResponse
        )
      }
    }
    "search for 'tcga brca openaccess'" - {
      "should find all openaccess datasets, plus the BRCA controlled access" in {
        // we'll match on 2 of the 3 tokens, so we find "tcga openaccess" as well as "tcga brca" and "brca openaccess"
        val searchResponse = searchFor("tcga brca openaccess")
        assertResult(13) {searchResponse.total}
        val actualNames = getResultField("library:datasetName", searchResponse)
        assert(
          actualNames.forall(name => name.equals("TCGA_BRCA_ControlledAccess") || name.endsWith("_OpenAccess"))
        )
      }
    }
    "search for 'kidney renal papillary cell carcinoma'" - {
      "should find four datasets with two types of kidney carcinomas" in {
        val searchResponse = searchFor("kidney renal papillary cell carcinoma")
        assertResult(4) {searchResponse.total}
        validateResultIndications(
          Set("Kidney Renal Clear Cell Carcinoma","Kidney Renal Papillary Cell Carcinoma"),
          searchResponse
        )
      }
    }
    "search for 'testing123'" - {
      "should find the single dataset named 'testing123'" in {
        val searchResponse = searchFor("testing123")
        assertResult(1) {searchResponse.total}
        validateResultNames(
          Set("testing123"),
          searchResponse
        )
      }
    }

  }

  private def searchFor(txt:String) = {
    val criteria = emptyCriteria.copy(searchString = Some(txt))
    Await.result(searchDAO.findDocuments(criteria, Seq.empty[String]), dur)
  }

  private def validateResultNames(expectedNames:Set[String], response:LibrarySearchResponse) = {
    validateResultField("library:datasetName", expectedNames, response)
  }

  private def validateResultIndications(expectedIndications:Set[String], response:LibrarySearchResponse) = {
    validateResultField("library:indication", expectedIndications, response)
  }
  private def validateResultField(attrName:String, expectedValues:Set[String], response:LibrarySearchResponse) = {
    val actualValues:Set[String] = getResultField(attrName, response)
    assertResult(expectedValues) {actualValues}
  }

  private def getResultField(attrName:String, response:LibrarySearchResponse):Set[String] = {
    (response.results map {jsval:JsValue =>
      jsval.asJsObject.fields(attrName).convertTo[String]
    }).toSet
  }

}