package io.spaship.operator.api;

// @todo We cannot use the TestHttpEndpoint unfortunately because it does not
// respect our `@ApplicationPath` annotation in `./config/RestConfig.java`.
// @see https://github.com/quarkusio/quarkus/issues/18289
//import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.junit.QuarkusTest; 
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.Method;
import io.spaship.operator.business.SPAUploadHandler;
import io.vertx.core.json.JsonObject;

import org.junit.jupiter.api.Test;

import org.mockito.Mockito;

import static io.restassured.RestAssured.expect;
import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import java.util.Map;
import java.util.UUID;

import org.javatuples.Pair;

// @todo We cannot use the TestHttpEndpoint unfortunately because it does not
// respect our `@ApplicationPath` annotation in `./config/RestConfig.java`.
// @see https://github.com/quarkusio/quarkus/issues/18289
//@TestHttpEndpoint(SpaDeploymentController.class)
@QuarkusTest
public class SpaDeploymentControllerTest {

  @InjectMock
  SPAUploadHandler spaUploadHandler;

  @Test
  @TestSecurity(authorizationEnabled = false)
  public void testUpload() {
    String path = "/api/upload";
    Method httpMethod = Method.GET;
    int expectedStatusCode = 501;

    given()
      .when().request(httpMethod, path)
      .then()
        .statusCode(expectedStatusCode);
  }

  @Test
  @TestSecurity(authorizationEnabled = false)
  public void testUploadSPA() {
    String path = "/api/upload";
    Method httpMethod = Method.POST;
    int expectedStatusCode = 200;

    UUID processId = UUID.randomUUID();
    String website = "one.redhat.com";
    Pair<String, UUID> requestTaggingReturnValue = new Pair<String, UUID>(website, processId);
    Mockito.when(this.spaUploadHandler.requestTagging(website)).thenReturn(requestTaggingReturnValue);
    JsonObject responseBody = new JsonObject(Map.of(
      "description", website,
      "traceId", processId
    ));

    given()
    .multiPart("spa", "../../resources/home-spa.zip")
    .multiPart("website", website)
    .multiPart("description", "testDescription")
      .when().request(httpMethod, path)
      .then()
        .statusCode(expectedStatusCode)
        .body(is(responseBody.toString()));
  }

  @Test
  @TestSecurity(authorizationEnabled = false)
  public void testException() {
    String path = "/api/upload/test-exception";
    Method httpMethod = Method.GET;
    int expectedStatusCode = 500;

    given()
    .when().request(httpMethod, path)
    .then()
      .statusCode(expectedStatusCode)
      .body(is("{\"errorMessage\":\"my custom illegal exception\",\"errorCode\":\"500\",\"documentationLink\":\"https://spaship.io/\"}"));
  }

  @Test
  @TestSecurity(authorizationEnabled = false)
  public void testDequeue() {
    String path = "/api/upload/dequeue/{website}";
    Method httpMethod = Method.GET;
    int expectedStatusCode = 200;
    String website = "one.redhat.com";

    given()
    .when().request(httpMethod, path, website)
    .then()
      .statusCode(expectedStatusCode)
      .body(is("false"));
  }

}