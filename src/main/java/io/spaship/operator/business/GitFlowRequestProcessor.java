package io.spaship.operator.business;


import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.spaship.operator.service.k8s.GitFlowResourceProvisioner;
import io.spaship.operator.type.*;
import io.spaship.operator.util.BuildConfigYamlModifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

@ApplicationScoped
public class GitFlowRequestProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(GitFlowRequestProcessor.class);
    private static final int NO_OF_RETRY = 6;
    private static final int RETRY_BACKOFF_DELAY = 1000;
    private final Executor executor = Infrastructure.getDefaultExecutor();

    private final GitFlowResourceProvisioner provisioner;


    public GitFlowRequestProcessor(GitFlowResourceProvisioner provisioner) {
        this.provisioner = provisioner;
        LOG.debug("provisioner injected");
    }



    // TODO get rid of excess stuffs since deployment is chained within processHandler hence trigger is not needed anymore
    public Uni<GitFlowResponse> trigger(GitFlowMeta meta) {
        final Function<GitFlowMeta, Uni<GitFlowResponse>> process = this::processHandler;
        return process.apply(meta);
    }

    public Multi<String> fetchBuildLog(FetchK8sInfoRequest request) { // TODO check if it can be wrapped inside K8sResourceLog Object
        var reader = provisioner.getBuildLog(request.objectName(), request.ns(), request.upto()); //TODO add resiliency in ase it gets triggred during the build of an Object it will throw error
        return Multi.createFrom().emitter(emitter -> {
            try (BufferedReader bufferedReader = new BufferedReader(reader)) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    if (emitter.isCancelled()) {
                        break;
                    }
                    emitter.emit(line);
                    emitter.emit("\n");
                }
                emitter.complete();
            } catch (IOException e) {
                emitter.fail(e);
            }
        });
    }

    public Uni<GeneralResponse<String>> readinessStatOfDeployment(FetchK8sInfoRequest request) {
        return Uni.createFrom().item(request).emitOn(executor).map(r -> {
            if (provisioner.deploymentReadiness(r.objectName(), r.ns()))
                return new GeneralResponse<>("Deployment is now running and ready for traffic."
                        , GeneralResponse.Status.OK);
            return new GeneralResponse<>("Unable to find or verify readiness of the deployment."
                    , GeneralResponse.Status.OK);
        });
    }

    public Uni<List<K8sResourceLog>> fetchDeploymentLog(FetchK8sInfoRequest request) { //TODO format the log just as fetchBuildLog
        return Uni.createFrom().item(request).emitOn(executor)
                .map(r -> provisioner.getTailingDeploymentLog(r.objectName(), r.ns(), r.upto())); ////TODO add resiliency in case it gets triggered during the build of an Object or during upscaling of build it will throw error
    }

    Uni<GitFlowResponse> processHandler(GitFlowMeta reqBody) {
        LOG.debug("inside method processHandler");
        return Uni.createFrom().item(() -> reqBody)
                .emitOn(executor)
                .flatMap(this::createOrReturnImageStreamWithResiliency)
                .flatMap(this::createOrUpdateBuildConfigWithResiliency)
                .flatMap(this::triggerBuildWithResiliency)
                .flatMap(this::deploymentHandler);
    }

    Uni<GitFlowResponse> deploymentHandler(GitFlowMeta preDeployment) {
        Uni<GitFlowResponse> response = Uni.createFrom().item(preDeployment)
                .map(this::buildResponse);
        deployAppAsync(response); //how to handle this side effect ??
        return response;
    }

    GitFlowMeta createOrReturnImageStream(GitFlowMeta input) {
        LOG.debug("inside method createOrReturnImageStream");
        boolean isAvailable = provisioner.imageStreamExists(input.imageStreamName(), input.nameSpace());
        if (!isAvailable) { // based on isAvailable it skips creating image stream
            LOG.info("cannot find the image stream, so creating a new one.");
            provisioner.provisionIs(input.toIsTemplateParameterMap(), input.nameSpace());
        }
        LOG.info("image stream already exists Skipping the creation");
        return input;
    }

    GitFlowMeta createOrUpdateBuildConfig(GitFlowMeta input) {
        LOG.debug("inside method createOrUpdateBuildConfig");
        if (input.reDeployment())  // based on this logic it skips BuildConfig create or update process.
            return input;
        LOG.debug("not a re-deployment");
        GitFlowMeta.BuildType type = input.typeOfBuild();
        try {
            InputStream is = generateBuildTemplate(input, type);
            provisionBuildConfig(is, input);
        } catch (IOException e) {
            LOG.error("An error was encountered during the processing of BuildType {} ", type);
            throw new RuntimeException(e);
        }

        return input;
    }

    private static InputStream generateBuildTemplate(GitFlowMeta input, GitFlowMeta.BuildType type) throws IOException {
        switch (type) {
            case MONO_WITH_BUILD_ARG, WITH_BUILD_ARG:
                return BuildConfigYamlModifier.monoRepoWithBuildArg(null, input.buildArgs());
            case MONO:
                return BuildConfigYamlModifier.modifyTemplateForMonRepo(null);
            case REGULAR:
                break;
            default:
                throw new IllegalArgumentException("Unsupported build type: " + type);
        }
        return null; // is this bad? todo use InputStream is = null on top and then return is instead of null
    }
    GitFlowMeta triggerBuild(GitFlowMeta input) {
        LOG.debug("inside method triggerBuild");
        var buildName = provisioner.triggerBuild(input.buildConfigName(), input.nameSpace());
        return input.newGitFlowMetaWithBuildName(buildName);
    }

    GitFlowResponse buildResponse(GitFlowMeta req) {
        return new GitFlowResponse(req.buildName(),req.deploymentName(),req);
    }

    private void deployAppAsync(Uni<GitFlowResponse> preDeployment) {
        LOG.debug("Handling the app deployment asynchronously");
        AtomicReference<GitFlowMeta> deploymentItem = new AtomicReference<>();
        preDeployment
                .flatMap(this::triggerDeploymentWithResiliency)
                .runSubscriptionOn(executor)
                .subscribe().with(
                        item -> {
                            LOG.debug("Deployment completed: {}", item);
                            deploymentItem.set(item);
                        },
                        ex -> {
                            LOG.error("Deployment failed: {}", ex.getMessage());
                            handleException(GitFlowStates.DEPLOYMENT_TRIGGER, deploymentItem.get(), ex);
                        }
                );
    }

    GitFlowMeta triggerDeployment(GitFlowResponse input) {
        LOG.debug("inside method triggerDeployment");
        try {
            Thread.sleep(6000); // TODO deployment implementation
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return input.constructedGitFlowMeta();
    }

    private void provisionBuildConfig(InputStream is, GitFlowMeta input) throws IOException {
        if (Objects.isNull(is)) {
            provisioner.createOrUpdateBuildConfig(input.toTemplateParameterMap(), input.nameSpace());
        } else {
            try (InputStream autoCloseableIs = is) {
                provisioner.createOrUpdateBuildConfig(autoCloseableIs, input.toTemplateParameterMap(), input.nameSpace());
            }
        }
    }


    private Uni<GitFlowMeta> createOrReturnImageStreamWithResiliency(GitFlowMeta req) {
        return applyResiliency(() -> createOrReturnImageStream(req), GitFlowStates.IS_CRETE, req);
    }

    private Uni<GitFlowMeta> createOrUpdateBuildConfigWithResiliency(GitFlowMeta req) {
        return applyResiliency(() -> createOrUpdateBuildConfig(req), GitFlowStates.BUILD_CFG_CREATE, req);
    }

    private Uni<GitFlowMeta> triggerBuildWithResiliency(GitFlowMeta req) {
        return applyResiliency(() -> triggerBuild(req), GitFlowStates.BUILD_TRIGGER, req);
    }

    private Uni<GitFlowMeta> triggerDeploymentWithResiliency(GitFlowResponse req) {
        return applyResiliency(() -> triggerDeployment(req),
                GitFlowStates.DEPLOYMENT_TRIGGER, req.constructedGitFlowMeta());
    }

    private <T> Uni<T> applyResiliency(Supplier<T> supplier, GitFlowStates state, GitFlowMeta req) {
        LOG.debug("inside method applyRetryAndDelay");
        return Uni.createFrom().item(supplier)
                .onFailure().retry().atMost(NO_OF_RETRY)
                .onItem().delayIt().by(Duration.ofMillis(RETRY_BACKOFF_DELAY))
                .onFailure().invoke(ex -> handleException(state, req, ex));
    }

    private void handleException(GitFlowStates state, GitFlowMeta req, Throwable exception) {
        //TODO pass this to SSE event
        LOG.error("An error occurred {} in stage {} GitFlowMeta {}", exception.getMessage(), state, req);
    }

    enum GitFlowStates {
        IS_CRETE, BUILD_CFG_CREATE, BUILD_TRIGGER, DEPLOYMENT_TRIGGER
    }
}
