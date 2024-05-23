package io.spaship.operator.api;

import io.quarkus.security.Authenticated;
import io.spaship.operator.business.SPAUploadHandler;
import io.spaship.operator.repo.SharedRepository;
import io.spaship.operator.type.FormData;
import io.vertx.core.json.JsonObject;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.javatuples.Triplet;
import org.jboss.resteasy.reactive.MultipartForm;
import org.jboss.resteasy.reactive.RestResponse;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.util.Map;

@Path("/upload")
@Authenticated
@ApplicationScoped
@RegisterRestClient
public class SpaDeploymentController {

  private final SPAUploadHandler spaUploadHandlerService;

  public SpaDeploymentController(SPAUploadHandler spaUploadHandlerService) {
    this.spaUploadHandlerService = spaUploadHandlerService;
  }

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public RestResponse<String> upload() {
    return RestResponse.status(Response.Status.NOT_IMPLEMENTED, "This HTTP method is not implement on this endpoint.");
  }

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  public RestResponse<JsonObject> uploadSPA(@MultipartForm FormData formData) {
    //[0]description[1]unique-trace-id
    this.spaUploadHandlerService.sanity(formData);
    var response = this.spaUploadHandlerService.requestTagging(formData.website);
    //[0]file-path[1]unique-trace-id[2]website-name
    var fileUploadParams = new Triplet<>(formData.getfilePath(), response, formData.website);
    spaUploadHandlerService.handleFileUpload(fileUploadParams);
    JsonObject object = new JsonObject(Map.of(
      "description", response.getValue0(),
      "traceId", response.getValue1()
    ));

    return RestResponse.ok(object);
  }

  @GET
  @Path("/test-exception")
  @Produces(MediaType.APPLICATION_JSON)
  public String exception() {
    throw new IllegalStateException("my custom illegal exception");
  }

  @GET
  @Path("/dequeue/{website}")
  @Produces(MediaType.TEXT_PLAIN)
  public Response dequeue(@PathParam("website") String website) {
    return Response.ok(String.valueOf(SharedRepository.dequeue(website))).build();
  }

}




