package io.spaship.operator.business;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.tuples.Tuple3;
import io.spaship.operator.service.k8s.SsrResourceProvisioner;
import io.spaship.operator.type.SsrResourceDetails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.concurrent.Executor;

@ApplicationScoped
public class SsrRequestProcessor {


    private static final Logger LOG = LoggerFactory.getLogger(SsrRequestProcessor.class);

    final SsrResourceProvisioner resourceProvisioner;
    private final Executor executor = Infrastructure.getDefaultExecutor();


    public SsrRequestProcessor(SsrResourceProvisioner resourceProvisioner) {
        this.resourceProvisioner = resourceProvisioner;
    }

    public Uni<Optional<String>> processSPAProvisionRequest(SsrResourceDetails requestPayload) {
        return Uni.createFrom().item(() -> requestPayload).emitOn(executor)
                .map(this::provision);
    }

    public Uni<Optional<String>> processUpdateRequest(SsrResourceDetails resourceDetails) {
        return Uni.createFrom().item(() -> resourceDetails).emitOn(executor)
                .map(this::update);
    }

    public Uni<Optional<String>> processSpaDeleteRequest(SsrResourceDetails requestPayload) {
        return Uni.createFrom().item(() -> requestPayload).emitOn(executor)
        .map(this::delete);
    }

    public Uni<Optional<String>> processConfigUpdateRequest(SsrResourceDetails resourceDetails) {
        return Uni.createFrom().item(() -> resourceDetails).emitOn(executor)
                .map(this::updateConfigMap);
    }




    //Lambda inner methods
    //TODO get rid of these ugly RuntimeExceptions.

    private Optional<String> provision(SsrResourceDetails requestPayload) {
        boolean  isProvisioned =  resourceProvisioner
            .createNewEnvironment(requestPayload.toTemplateParameterMap(),requestPayload.nameSpace());
        if(isProvisioned)
            return Optional.of("provisioned");
        throw new RuntimeException("failed to provision resouce: ".concat(requestPayload.toString()));
    }

    private Optional<String> update(SsrResourceDetails resourceDetails) {
        LOG.info("the control reached with the information {}",resourceDetails);
        String uuid = UUID.randomUUID().toString();
        String deploymentName = resourceDetails.website()
                .concat("-").concat(resourceDetails.app())
                .concat("-").concat(resourceDetails.environment());
        LOG.info("computed deployment name is {}",deploymentName);
        resourceProvisioner.updateEnvironment(deploymentName,
                resourceDetails.imageUrl(),resourceDetails.nameSpace());
        return Optional.of(uuid);
    }

    private Optional<String> delete(SsrResourceDetails resourceDetails) {
        Map<String, String> payload = resourceDetails.toTemplateParameterMap();
        String ns = resourceDetails.nameSpace();
        boolean status = resourceProvisioner.deleteExistingEnvironment(payload,ns);
        if(status)
            return Optional.of("successfully deleted");
        throw new RuntimeException("failed to delete resouce: ".concat(resourceDetails.toString()));
    }

    private Optional<String> updateConfigMap(SsrResourceDetails resourceDetails) {
        var labels =  Tuple3.of(resourceDetails.website(),
                resourceDetails.app(),resourceDetails.environment());
        var updateMapStatus = resourceProvisioner.updateConfigMapOf(labels,
                resourceDetails.configMap(),resourceDetails.nameSpace());
        if(updateMapStatus)
            return Optional.of("successfully updated");
        throw new RuntimeException("failed to update resouce: ".concat(resourceDetails.toString()));
    }


}
