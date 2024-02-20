package io.spaship.operator.business;


import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import io.spaship.operator.config.SPAShipThreadPool;
import io.spaship.operator.service.k8s.GitFlowResourceProvisioner;
import io.spaship.operator.type.*;
import io.spaship.operator.util.BuildConfigYamlModifier;
import io.spaship.operator.util.ReUsableItems;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;


@ApplicationScoped
public class GitFlowRequestProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(GitFlowRequestProcessor.class);
    private static final int NO_OF_RETRY = 6;
    private static final int RETRY_BACKOFF_DELAY = 3000;

    private final GitFlowResourceProvisioner provisioner;
    private final SsrRequestProcessor cdProcessor;
    private final EventManager eventManager;

    ExecutorService gitFlowExecutorSvc = SPAShipThreadPool.cachedThreadPool();



    public GitFlowRequestProcessor(GitFlowResourceProvisioner provisioner, SsrRequestProcessor cdProcessor, EventManager eventManager) {
        this.provisioner = provisioner;
        this.cdProcessor = cdProcessor;
        this.eventManager = eventManager;
        LOG.debug("provisioner injected");
    }

    private static GeneralResponse<String> exceptionDuringReadinessCheck(Throwable err) {
        return new GeneralResponse<>("Failed to check deployment status due to ".concat(err.getMessage())
                , GeneralResponse.Status.ERR);
    }


    // TODO get rid of excess stuffs since deployment is chained within processHandler hence trigger is not needed anymore
    public Uni<GitFlowResponse> trigger(GitFlowMeta meta) {
        final Function<GitFlowMeta, Uni<GitFlowResponse>> process = this::processHandler;
        return process.apply(meta);
    }

    /**
     * Creates a repository of functions for fetching logs based on the LogType.
     * Each function in the repository takes a request object named FetchK8sInfoRequest as input and
     * returns a list of log lines.The repository is a Map where the key is the LogType and the value
     * is the corresponding function.
     * @return A Map of LogType to Function, where each function fetches logs based on the FetchK8sInfoRequest input.
     */
    Map<LogType,Function<FetchK8sInfoRequest,List<String>>>  logFunctionRepository(){
        boolean isRemoteBuild = ReUsableItems.isRemoteBuild();
        Map<LogType,Function<FetchK8sInfoRequest,List<String>>> readLogFunctionList= new EnumMap<>(LogType.class);
        readLogFunctionList.put(LogType.BUILD,
                input -> {
                    String ns = input.ns();
                    if(isRemoteBuild)
                        ns = ReUsableItems.remoteBuildNameSpace();
                    return readLog(provisioner.getBuildLog(input.objectName(),ns, input.upto(),isRemoteBuild));
                });
        readLogFunctionList.put(LogType.DEPLOYMENT,
                input -> readLog(provisioner.getDeploymentLog(input.objectName(), input.ns(),
                        input.upto(),false)));
        readLogFunctionList.put(LogType.HTTP_DEPLOYMENT,
                input -> readLog(provisioner.getDeploymentLog(input.objectName(), input.ns(),
                        input.upto(),true)));
        readLogFunctionList.put(LogType.POD,
                input -> readLog(provisioner.getLog(input.objectName(), input.ns(), input.upto(),false)));
        readLogFunctionList.put(LogType.HTTP_POD,
                input -> readLog(provisioner.getLog(input.objectName(), input.ns(), input.upto(),true)));
        return readLogFunctionList;
    }

    public Multi<String> fetchLogByType(FetchK8sInfoRequest request,LogType logType){
        return Uni.createFrom().item(() -> logFunctionRepository().get(logType).apply(request))
                .runSubscriptionOn(gitFlowExecutorSvc)
                .onItem().transformToMulti(lines -> Multi.createFrom().iterable(lines))
                .flatMap(line -> Multi.createFrom().items(line, "\n"));
    }

    public Uni<GeneralResponse<List<String>>> listPods(Optional<String> deploymentName, Optional<String> ns) {
        var deployment = deploymentName.orElseThrow();
        var nameSpace = ns.orElseThrow();
        return Uni.createFrom().item( ()-> new GeneralResponse<>(
                provisioner.getPodNames(deployment,nameSpace),GeneralResponse.Status.ACCEPTED)
                )
                .runSubscriptionOn(gitFlowExecutorSvc);
    }

    private List<String> readLog(Reader reader) {
        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            List<String> lines = bufferedReader.lines().toList();
            LOG.debug("to check in which thread is this running on");
            return lines;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public Uni<GeneralResponse<String>> readinessStatOfDeployment(FetchK8sInfoRequest request) {
        return Uni.createFrom().item(request).emitOn(gitFlowExecutorSvc).map(r ->
                {
                    if (provisioner.deploymentIsReady(r.objectName(), r.ns()))
                        return new GeneralResponse<>("Deployment is now running and ready for traffic."
                                , GeneralResponse.Status.READY);
                    return new GeneralResponse<>("Deployment is in progress."
                            , GeneralResponse.Status.IN_PROGRESS);
                })
                .onFailure()
                .retry()
                .atMost(NO_OF_RETRY)
                .onItem().delayIt().by(Duration.ofMillis(RETRY_BACKOFF_DELAY))
                .onFailure()
                .recoverWithItem(GitFlowRequestProcessor::exceptionDuringReadinessCheck);
    }

    public Uni<GeneralResponse<String>> checkBuildPhase(FetchK8sInfoRequest reqBody) {
        return Uni.createFrom().item(reqBody).emitOn(gitFlowExecutorSvc).map(item->{
            boolean isRemoteBuild = ReUsableItems.isRemoteBuild();
            String ns = null;
            if(isRemoteBuild){
                ns = ReUsableItems.remoteBuildNameSpace();
            }else{
                ns = item.ns();
            }
            var phase = provisioner.checkBuildPhase(item.objectName(),ns,isRemoteBuild);
            return switch (phase) {
                case "Pending", "New" -> new GeneralResponse<>(BuildStatus.STUCK.toString(), GeneralResponse.Status.OK);
                case "Running" -> new GeneralResponse<>(BuildStatus.IN_PROGRESS.toString(), GeneralResponse.Status.OK);
                case "Complete" -> new GeneralResponse<>(BuildStatus.COMPLETED.toString(), GeneralResponse.Status.OK);
                case "Failed" -> new GeneralResponse<>(BuildStatus.FAILED.toString(), GeneralResponse.Status.OK);
                case "NF" -> new GeneralResponse<>(BuildStatus.NOT_FOUND.toString(), GeneralResponse.Status.ERR);
                default -> new GeneralResponse<>(BuildStatus.CHECK_OS_CONSOLE.toString(), GeneralResponse.Status.OK);
            };
        });
    }

    Uni<GitFlowResponse> processHandler(GitFlowMeta reqBody) {
        LOG.debug("inside method processHandler");
        return Uni.createFrom().item(() -> reqBody)
                .emitOn(gitFlowExecutorSvc)
                .flatMap(this::checkProjectExistenceWithResiliency)
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


    GitFlowMeta checkProjectExistence(GitFlowMeta input) {
        LOG.debug("inside method checkProjectExistence");
        cdProcessor.nameSpaceInspection(input.nameSpace());
        return input;
    }


    GitFlowMeta createOrReturnImageStream(GitFlowMeta input) {
        LOG.debug("inside method createOrReturnImageStream");
        if(ReUsableItems.isRemoteBuild()){
            LOG.info("remote build detected skipping the image stream");
            return input;
        }
        boolean isAvailable = provisioner.imageStreamExists(input.imageStreamName(), input.nameSpace());
        if (!isAvailable) { // based on isAvailable it skips creating image stream
            LOG.info("cannot find the image stream, so creating a new one.");
            provisioner.provisionIs(input.toIsTemplateParameterMap(), input.nameSpace());
        }
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
            if(ReUsableItems.isRemoteBuild()){
                LOG.info("remote build detected");
                is = BuildConfigYamlModifier.modifyForRemoteBuild(is); //TODO Modifying the reference is not a good idea, refactoring is required?
            }
            provisionBuildConfig(is, input,ReUsableItems.isRemoteBuild());
        } catch (IOException e) {
            LOG.error("An error was encountered during the processing of BuildType {} ", type);
            throw new RuntimeException(e);
        }

        return input;
    }

    private static InputStream generateBuildTemplate(GitFlowMeta input, GitFlowMeta.BuildType type) throws IOException {
        return switch (type){
            case MONO_WITH_BUILD_ARG -> BuildConfigYamlModifier.monoRepoWithBuildArg(null,
                    input.buildArgs());
            case WITH_BUILD_ARG -> BuildConfigYamlModifier.modifyTemplateForDockerBuildArg(null,
                    input.buildArgs());
            case MONO -> BuildConfigYamlModifier.modifyTemplateForMonRepo(null);
            case REGULAR -> null;
            default -> throw new IllegalArgumentException("Unsupported build type: " + type);
        };
    }
    GitFlowMeta triggerBuild(GitFlowMeta input) {
        LOG.debug("inside method triggerBuild");
        String buildName = null;

        if(ReUsableItems.isRemoteBuild()){
            buildName = provisioner
                    .triggerBuildWrapper(input.buildConfigName(),ReUsableItems.remoteBuildNameSpace(),true);
            return input.newGitFlowMetaWithBuildName(buildName);
        }
        buildName = provisioner
                .triggerBuildWrapper(input.buildConfigName(), input.nameSpace(),false);
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
                .runSubscriptionOn(gitFlowExecutorSvc)
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
        var buildName = input.buildName();
        var project = input.fetchNameSpace();
        boolean isRemoteBuild = ReUsableItems.isRemoteBuild();
        if(isRemoteBuild){
            project = ReUsableItems.remoteBuildNameSpace();
            waitForBuildEnd(buildName,project,input.fetchDeploymentDetails(),true);
        }else{
            waitForBuildEnd(buildName,project,input.fetchDeploymentDetails(),false);
        }
        if(!provisioner.isBuildSuccessful(buildName,project,isRemoteBuild)){
            LOG.warn("Build {} failed. Please check the openshift console " +
                    "for more details. The execution will end here, " +
                    "and deployment will be skipped.",buildName);
            LOG.debug("scheduling DEPLOYMENT_CANCELLED event");
            eventManager.queue(EventStructure.builder()
                    .websiteName(input.fetchDeploymentDetails().website())
                    .environmentName(input.fetchDeploymentDetails().environment())
                    .uuid(UUID.randomUUID().toString())
                    .state(ExecutionStates.DEPLOYMENT_CANCELLED.toString())
                    .spaName(input.fetchDeploymentDetails().app())
                    .contextPath(input.fetchDeploymentDetails().contextPath())
                    .build()
            );
            return input.constructedGitFlowMeta();
        }
        LOG.debug("Build is successful, continuing the deployment");
        var outcome = cdProcessor.processSPAProvisionRequest(input.fetchDeploymentDetails());
        outcome.subscribe().with(a ->{
            eventManager.queue(EventStructure.builder()
                    .websiteName(a.getString("website"))
                    .environmentName(a.getString("environment"))
                    .uuid(UUID.randomUUID().toString())
                    .state(ExecutionStates.DEPLOYMENT_STARTED.toString())
                    .spaName(a.getString("application"))
                    .contextPath(a.getString("accessUrl"))
                    .build()
            );
            LOG.debug("DEPLOYMENT_STARTED Event queued");
        });
        return input.constructedGitFlowMeta();
    }

    void waitForBuildEnd(String buildName, String ns, SsrResourceDetails deploymentDetails, boolean remoteBuild){
        LOG.info("waiting for build: {} in project {} to complete",buildName,ns);
        int attempt =0;
        int buildAttemptLimit = 1000;
        while(true){
            LOG.debug("waiting for build attempt {}",attempt);
            if(attempt> buildAttemptLimit){
                LOG.warn("forcefully exiting the build");
                var buildMeta = provisioner.fetchBuildMeta(buildName,ns,ReUsableItems.isRemoteBuild());
                buildMeta.put("Description","Force exited from code to prevent from infinite looping");
                eventManager.queue(EventStructure.builder()
                        .websiteName(deploymentDetails.website())
                        .environmentName(deploymentDetails.environment())
                        .uuid(UUID.randomUUID().toString())
                        .state(ExecutionStates.FORCE_EXITED.toString())
                        .spaName(deploymentDetails.app())
                        .contextPath("NA")
                        .meta(buildMeta)
                        .build()
                );
                throw new RuntimeException(
                        "Force exiting from the check build process after "+buildAttemptLimit+" " +
                                "tries to prevent this process from further resource blocking");
            }
            if(provisioner.hasBuildEnded(buildName,ns,remoteBuild)){
                LOG.info("exiting from the while loop, the build has been ended");
                eventManager.queue(EventStructure.builder()
                        .websiteName(deploymentDetails.website())
                        .environmentName(deploymentDetails.environment())
                        .uuid(UUID.randomUUID().toString())
                        .state(ExecutionStates.BUILD_ENDED.toString())
                        .spaName(deploymentDetails.app())
                        .contextPath("NA")
                        .meta(provisioner.fetchBuildMeta(buildName,ns,ReUsableItems.isRemoteBuild()))
                        .build()
                );
                break;
            }
            try {
                Thread.sleep(10000);
                attempt++;
            } catch (InterruptedException e) {
                throw new RuntimeException("failed to wait till build is complete "+e.getMessage());
            }
        }
    }

    private void provisionBuildConfig(InputStream is, GitFlowMeta input, boolean isRemoteBuild) throws IOException {
        if(isRemoteBuild){
            try (InputStream autoCloseableIs = is) {
                provisioner.createOrUpdateRemoteBuildConfig(
                        autoCloseableIs,input.toTemplateParameterMap(),ReUsableItems.remoteBuildNameSpace()
                );
            }
            return;
        }
        if (Objects.isNull(is)) {
            provisioner.createOrUpdateBuildConfig(input.toTemplateParameterMap(), input.nameSpace());
        } else {
            try (InputStream autoCloseableIs = is) {
                provisioner.createOrUpdateBuildConfig(
                        autoCloseableIs, input.toTemplateParameterMap(), input.nameSpace()
                );
            }
        }
    }


    private Uni<GitFlowMeta> checkProjectExistenceWithResiliency(GitFlowMeta req) {
        return applyResiliency(() -> checkProjectExistence(req), GitFlowStates.PROJECT_CHECK, req);
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
        PROJECT_CHECK,IS_CRETE, BUILD_CFG_CREATE, BUILD_TRIGGER, DEPLOYMENT_TRIGGER
    }
    enum ExecutionStates {
        BUILD_ENDED,DEPLOYMENT_STARTED,DEPLOYMENT_CANCELLED,FORCE_EXITED
    }
    enum BuildStatus{
        STUCK,IN_PROGRESS,COMPLETED,FAILED,CHECK_OS_CONSOLE,NOT_FOUND
    }
    public enum LogType{
        BUILD,DEPLOYMENT,POD,HTTP_POD,HTTP_DEPLOYMENT
    }
}
