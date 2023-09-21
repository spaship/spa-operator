package io.spaship.operator.api;

import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.RouteBase;
import io.quarkus.vertx.web.RoutingExchange;
import io.smallrye.mutiny.Uni;
import io.spaship.operator.business.SsrRequestProcessor;
import io.spaship.operator.type.SsrResourceDetails;
import io.spaship.operator.type.UpdateConfigOrSecretRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.util.Optional;

import io.quarkus.security.Authenticated;


@ApplicationScoped
@RouteBase(path = "api/deployment/v1")
@Authenticated
public class SsrRoute {

    private static final Logger LOG = LoggerFactory.getLogger(SsrRoute.class);
    final SsrRequestProcessor requestProcessor;

    public SsrRoute(SsrRequestProcessor requestProcessor) {
        this.requestProcessor = requestProcessor;
    }


    @Route(path = "/create", methods = Route.HttpMethod.POST)
    Uni<JsonObject> triggerDeployment(RoutingContext rc) {
        var reqBody = rc.body().asPojo(SsrResourceDetails.class);
        return requestProcessor.processSPAProvisionRequest(reqBody);
    }

    @Route(path = "/delete", methods = Route.HttpMethod.DELETE)
    Uni<Optional<JsonObject>> deleteDeployment(RoutingContext rc) {
        var reqBody = rc.body().asPojo(SsrResourceDetails.class);
        return requestProcessor.processSpaDeleteRequest(reqBody);
    }

    @Route(path = "/update", methods = Route.HttpMethod.PUT)
    Uni<Optional<JsonObject>> updateDeployment(RoutingContext rc) {
        var reqBody = rc.body().asPojo(SsrResourceDetails.class);
        LOG.info("the following request received in the router {}",reqBody);
        return requestProcessor.processUpdateRequest(reqBody);
    }

    @Route(path = "/config", methods = Route.HttpMethod.POST)
    Uni<Optional<JsonObject>> updateConfig(RoutingContext rc) {
        var reqBody = rc.body().asPojo(UpdateConfigOrSecretRequest.class);
        return requestProcessor.processConfigUpdateRequest(reqBody);
    }

    @Route(path = "/secret", methods = Route.HttpMethod.POST)
    Uni<Optional<JsonObject>> updateSecret(RoutingContext rc) {
        var reqBody = rc.body().asPojo(UpdateConfigOrSecretRequest.class);
        return requestProcessor.processSecretUpdateRequest(reqBody);
    }

    @Route(path = "/", methods = Route.HttpMethod.GET)
    void rootPath(RoutingExchange ex){
        ex.ok("ssr config status : online");
    }

}
