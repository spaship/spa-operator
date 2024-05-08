package io.spaship.operator.business;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.tuples.Tuple3;
import io.spaship.operator.exception.SsrException;
import io.spaship.operator.service.k8s.SsrResourceProvisioner;
import io.spaship.operator.type.K8sObjects;
import io.spaship.operator.type.SsrResourceDetails;
import io.spaship.operator.type.UpdateConfigOrSecretRequest;
import io.spaship.operator.util.BuildConfigYamlModifier;
import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.Executor;

@ApplicationScoped
public class SsrRequestProcessor {


    private static final Logger LOG = LoggerFactory.getLogger(SsrRequestProcessor.class);
    private static final String STATUS = "status";  

    final SsrResourceProvisioner resourceProvisioner;
    private final Executor executor = Infrastructure.getDefaultExecutor();


    public SsrRequestProcessor(SsrResourceProvisioner resourceProvisioner) {
        this.resourceProvisioner = resourceProvisioner;
    }

    public void nameSpaceInspection(String ns){
        resourceProvisioner.nameSpace(ns);
    }
    public Uni<JsonObject> processSPAProvisionRequest(SsrResourceDetails requestPayload) {
        return Uni.createFrom().item(() -> requestPayload).emitOn(executor)
                .map(this::provision);
    }

    public Uni<Optional<JsonObject>> processUpdateRequest(SsrResourceDetails resourceDetails) {
        return Uni.createFrom().item(() -> resourceDetails).emitOn(executor)
                .map(this::update);
    }

    public Uni<Optional<JsonObject>> processSpaDeleteRequest(SsrResourceDetails requestPayload) {
        return Uni.createFrom().item(() -> requestPayload).emitOn(executor)
        .map(this::delete);
    }

    public Uni<Optional<JsonObject>> processConfigUpdateRequest(UpdateConfigOrSecretRequest request) {
        return Uni.createFrom().item(() -> request).emitOn(executor)
                .map(this::updateConfigMap);
    }
    public Uni<Optional<JsonObject>> processSecretUpdateRequest(UpdateConfigOrSecretRequest request) {
        return Uni.createFrom().item(() -> request).emitOn(executor)
                .map(this::updateSecret);
    }




    //Lambda inner methods

    private JsonObject provision(SsrResourceDetails requestPayload) { //TODO change the return type get rid of Optional and Use a defined type instead of JsonObject; get rid of imperative style coding
        var parameters = requestPayload.toTemplateParameterMap();
        boolean  isProvisioned;
        boolean hasConfigMap = Objects.nonNull(requestPayload.configMap()) && (!requestPayload.configMap().isEmpty());
        boolean hasSecret = Objects.nonNull(requestPayload.secretMap()) && (!requestPayload.secretMap().isEmpty());

        if(hasConfigMap && hasSecret){
            LOG.info("This deployment has both configmap and secret");
            InputStream isWIthConfig = BuildConfigYamlModifier.addDataToConfigMap(
                    null,requestPayload.configMap());
            InputStream isWithConfigAndSecret = BuildConfigYamlModifier.addDataToSecretMap(
                    isWIthConfig,requestPayload.secretMap());
            isProvisioned = resourceProvisioner.createNewEnvironment(
                    isWithConfigAndSecret,parameters,requestPayload.nameSpace());
        }else if(hasConfigMap){
            LOG.info("This deployment has a configmap");
            InputStream is = BuildConfigYamlModifier.addDataToConfigMap(null,requestPayload.configMap());
            isProvisioned = resourceProvisioner.createNewEnvironment(is,parameters,requestPayload.nameSpace());
        }else if(hasSecret){
            LOG.info("This deployment has a Secret");
            InputStream is = BuildConfigYamlModifier.addDataToSecretMap(null,requestPayload.secretMap());
            isProvisioned = resourceProvisioner.createNewEnvironment(is,parameters,requestPayload.nameSpace());
        }else{
            isProvisioned = resourceProvisioner.createNewEnvironment(null,parameters,requestPayload.nameSpace());
        }

        if(isProvisioned){
            var constructedUrl = constructAccessUrl(parameters);
            LOG.info("constructed access url is {}",constructedUrl);
            return new JsonObject()
                    .put("website",requestPayload.website())
                    .put("application",requestPayload.app())
                    .put("environment",requestPayload.environment())
                    .put(STATUS, "In-Progress")
                    .put("accessUrl",constructedUrl);
        }
        throw new SsrException("failed to provision resouce: ".concat(requestPayload.toString()));
    }

    String constructAccessUrl(Map<String, String> parameters) {
        var website = parameters.get("WEBSITE");
        var env = parameters.get("ENV");
        var routeDomain = parameters.get("ROUTER-DOMAIN");
        var contextPath = parameters.get("CONTEXT-PATH");

        LOG.info("<website>-<env>.<routedomain><context path> {}-{}.{}{}",website,env,routeDomain,contextPath);

        if(Objects.isNull(website) || Objects.isNull(env) || Objects.isNull(routeDomain) || Objects.isNull(contextPath))
            throw new SsrException("website or env or domain is missing");

        return  "https://".concat(website)
        .concat("-").concat(env)
        .concat(".").concat(routeDomain).concat(contextPath);
    }

    private Optional<JsonObject> update(SsrResourceDetails resourceDetails) {
        LOG.info("the control reached with the information {}",resourceDetails);
        String deploymentName = resourceDetails.website()
                .concat("-").concat(resourceDetails.app())
                .concat("-").concat(resourceDetails.environment());
        LOG.info("computed deployment name is {}",deploymentName);
        resourceProvisioner.updateEnvironment(deploymentName,
                resourceDetails.imageUrl(),resourceDetails.nameSpace());
        var response = new JsonObject().put(STATUS, "updated");
        return Optional.of(response);
    }

    private Optional<JsonObject> delete(SsrResourceDetails resourceDetails) {
        Map<String, String> payload = resourceDetails.toTemplateParameterMap();
        String ns = resourceDetails.nameSpace();
        boolean status = resourceProvisioner.deleteExistingEnvironment(payload,ns);
        if(status){
            var response = new JsonObject().put(STATUS, "deleted");
            return Optional.of(response);
        }
            
        throw new SsrException("failed to delete resouce: ".concat(resourceDetails.toString()));
    }

    private Optional<JsonObject> updateConfigMap(UpdateConfigOrSecretRequest request) {
        var labels =  Tuple3.of(request.ssrResourceDetails().website(),
                request.ssrResourceDetails().app(),request.ssrResourceDetails().environment());
        var updateMapStatus = resourceProvisioner.updateAppConfigAndResetPod(labels,
                request.ssrResourceDetails().configMap(),request.keysToDelete(),
                request.ssrResourceDetails().nameSpace(),K8sObjects.CONFIG_MAP);
        if(updateMapStatus){
            var response = new JsonObject().put(STATUS, "updated");
            return Optional.of(response);
        }
            
        throw new SsrException("failed to update resouce: ".concat(request.ssrResourceDetails().toString()));
    }

    private Optional<JsonObject> updateSecret(UpdateConfigOrSecretRequest request) {
        var labels =  Tuple3.of(request.ssrResourceDetails().website(),
                request.ssrResourceDetails().app(),request.ssrResourceDetails().environment());
        var updateMapStatus = resourceProvisioner.updateAppConfigAndResetPod(labels,
                request.ssrResourceDetails().secretMap(),request.keysToDelete(),
                request.ssrResourceDetails().nameSpace(), K8sObjects.SECRET_MAP);
        if(updateMapStatus){
            var response = new JsonObject().put(STATUS, "updated");
            return Optional.of(response);
        }

        throw new SsrException("failed to update resouce: ".concat(request.ssrResourceDetails().toString()));
    }




}
