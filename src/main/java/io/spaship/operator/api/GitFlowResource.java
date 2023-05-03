package io.spaship.operator.api;


import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.RouteBase;
import io.quarkus.vertx.web.RoutingExchange;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.spaship.operator.business.GitFlowRequestProcessor;
import io.spaship.operator.type.*;
import io.vertx.ext.web.RoutingContext;

import javax.enterprise.context.ApplicationScoped;
import java.util.List;


@ApplicationScoped
@RouteBase(path = "api/gf/v1")
public class GitFlowResource {

    private final GitFlowRequestProcessor grp;

    public GitFlowResource(GitFlowRequestProcessor requestProcessor) {
        this.grp = requestProcessor;
    }

    @Route(path = "", methods = Route.HttpMethod.GET)
    void rootPath(RoutingExchange ex) {
        ex.ok("workflow 3.0 armed");
    }

    @Route(path = "/init", methods = Route.HttpMethod.POST)
    Uni<GitFlowResponse> init(RoutingContext rc) {
        var reqBody = rc.body().asPojo(GitFlowMeta.class);
        return grp.trigger(reqBody);
    }

    @Route(path = "/deployment-status", methods = Route.HttpMethod.POST)
    Uni<GeneralResponse<String>> deploymentStats(RoutingContext rc) {
        var reqBody = rc.body().asPojo(FetchK8sInfoRequest.class);
        return grp.readinessStatOfDeployment(reqBody);
    }
    @Route(path = "/build-status", methods = Route.HttpMethod.POST)
    Uni<GeneralResponse<String>> buildStats(RoutingContext rc) {
        var reqBody = rc.body().asPojo(FetchK8sInfoRequest.class);
        return grp.checkBuildPhase(reqBody) ;
    }

    @Route(path = "/build-log", methods = Route.HttpMethod.POST)
    Multi<String> fetchBuildLog(RoutingContext rc) {
        var k8sInfoRequest = rc.body().asPojo(FetchK8sInfoRequest.class);
        return grp.fetchBuildLog(k8sInfoRequest);
    }

    @Route(path = "/deployment-log", methods = Route.HttpMethod.POST)
    Uni<List<K8sResourceLog>> fetchDeploymentLog(RoutingContext rc) {
        var reqBody = rc.body().asPojo(FetchK8sInfoRequest.class);
        return grp.fetchDeploymentLog(reqBody);
    }

}