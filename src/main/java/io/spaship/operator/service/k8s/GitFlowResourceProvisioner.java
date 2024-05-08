package io.spaship.operator.service.k8s;

import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.openshift.api.model.BuildRequest;
import io.fabric8.openshift.api.model.BuildRequestBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.spaship.operator.util.ReUsableItems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.InputStream;
import java.io.Reader;
import java.util.*;
import java.util.function.Function;


@ApplicationScoped
//todo extract all Logs related functions to another class, separate the concerns
public class GitFlowResourceProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(GitFlowResourceProvisioner.class);
    private static final String IMAGE_STREAM_TEMPLATE_LOCATION = "/openshift/is-template.yaml";
    private static final String BUILD_CONFIG_TEMPLATE_LOCATION = "/openshift/build-template.yaml";
    private final OpenShiftClient openShiftClient;
    private final OpenShiftClient remoteBuildClient;
    private static final String CONTAINER_NAME = "httpd-server";

    public GitFlowResourceProvisioner(@Named("default")OpenShiftClient openShiftClient,
                                      @Named("build")OpenShiftClient remoteBuildClient) {
        this.openShiftClient = openShiftClient;
        this.remoteBuildClient = remoteBuildClient;
    }


    public void provisionIs(Map<String, String> templateParam, String nameSpace) {
        var is = openShiftClient
                .templates()
                .load(GitFlowResourceProvisioner.class.getResourceAsStream(IMAGE_STREAM_TEMPLATE_LOCATION))
                .processLocally(templateParam);
        createOrReplaceBuild(is, nameSpace, "ImageStream created successfully. Output: {}");
    }

    public boolean imageStreamExists(String imageStreamName, String nameSpace) {
        LOG.debug("called method imageStreamExists()");
        return openShiftClient.imageStreams().inNamespace(nameSpace).withName(imageStreamName).isReady();
    }

    public void createOrUpdateBuildConfig(Map<String, String> templateParameterMap, String namespace) {

        var buildConfig = openShiftClient
                .templates()
                .load(GitFlowResourceProvisioner.class.getResourceAsStream(BUILD_CONFIG_TEMPLATE_LOCATION))
                .processLocally(templateParameterMap);
        createOrReplaceBuild(buildConfig, namespace, "Simple BuildConfig created successfully. Output: {}");
    }

    public void createOrUpdateBuildConfig(InputStream is, Map<String, String> templateParameterMap, String ns) {
        var buildConfig = openShiftClient
                .templates()
                .load(is)
                .processLocally(templateParameterMap);
        createOrReplaceBuild(buildConfig, ns, "Advanced BuildConfig created successfully. Output: {}");
    }


    public String triggerBuildWrapper(String buildConfigName, String ns, boolean isRemote){
        OpenShiftClient selectedClient = openShiftClient;
        if(isRemote){
            LOG.debug("Remote build client selected ");
            selectedClient = remoteBuildClient;
        }
        return triggerBuild(buildConfigName,ns,selectedClient);
    }

    private String triggerBuild(String buildConfigName, String ns, OpenShiftClient client) {
        BuildRequest buildRequest = new BuildRequestBuilder()
                .withNewMetadata()
                .withName(buildConfigName)
                .endMetadata()
                .build();
        var buildConfig = client.buildConfigs().inNamespace(ns).withName(buildConfigName).isReady();
        throwExceptionWhenConditionNotMatched(buildConfig,
                "BuildConfig resource named " + buildConfigName + " in namespace " + ns + " not found ");
        var build = client.buildConfigs().inNamespace(ns).withName(buildConfigName).instantiate(buildRequest);
        Objects.requireNonNull(build,
                "something went wrong with build created from " + buildConfigName + " in namespace " + ns);
        return build.getMetadata().getName();
    }


    public Reader getBuildLog(String buildName, String ns, int upto, boolean isRemoteBuild) {
        LOG.debug("getBuildLog called with buildName: {}; namespace: {}; upto: {}", buildName, ns, upto);

        if (upto <= 0)
            return getCompleteBuildLog(buildName, ns,isRemoteBuild);
        return getTailingBuildLog(buildName, ns, upto,isRemoteBuild);
    }

    public boolean deploymentIsReady(String deploymentName, String ns) {
        LOG.debug("Invoked method deploymentReadiness with deploymentName: {}, in Namespace: {}", deploymentName, ns);
        var deployment = openShiftClient.apps().deployments().inNamespace(ns).withName(deploymentName).get();
        var listOfConditions = deployment.getStatus().getConditions();
        var condition = listOfConditions.stream().filter(c-> "Available".equals(c.getType()))
                .findFirst().orElse(null);
        return Objects.nonNull(condition) && "True".equals(condition.getStatus());
    }

    public Reader getDeploymentLog(String deploymentName, String ns, int upto, boolean isHttpDeployment){
        LOG.debug("getDeploymentLog called with deploymentName: {}; namespace: {}; upto: {}", deploymentName, ns, upto);
        Function<OpenShiftClient, Reader> deploymentLogFunction = client -> client.apps().deployments().inNamespace(ns).withName(deploymentName)
                .tailingLines(upto).withPrettyOutput().getLogReader();
        Function<OpenShiftClient, Reader> httpDeploymentFunction = client -> client.apps().deployments().inNamespace(ns)
                .withName(deploymentName).inContainer(CONTAINER_NAME).getLogReader();

        if (isHttpDeployment)
            return httpDeploymentFunction.apply(openShiftClient);
        return deploymentLogFunction.apply(openShiftClient);
    }

    public Reader getLog(String podName, String ns, int upto, boolean isHttpPod){
        LOG.debug("getLog called with podName: {}; namespace: {}; upto: {}", podName, ns, upto);
        Function<OpenShiftClient, Reader> podLogFunction = client -> client.pods().inNamespace(ns).withName(podName)
                .tailingLines(upto).withPrettyOutput().getLogReader();
        Function<OpenShiftClient, Reader> httpPodLogFunction = client -> client.pods().inNamespace(ns)
                .withName(podName).inContainer(CONTAINER_NAME).tailingLines(upto).withPrettyOutput().getLogReader();
        if(isHttpPod)
            return httpPodLogFunction.apply(openShiftClient);
        return podLogFunction.apply(openShiftClient);
    }



    // TODO simplify this method, use function composition style or break into sub methods
    public List<String> getPodNames(String deploymentName, String ns) {
        LOG.debug("Invoked method getPodNames with deploymentName: {}, in Namespace: {}"
                , deploymentName, ns);
        var deployment = openShiftClient.apps().deployments().inNamespace(ns).withName(deploymentName).get();
        Objects.requireNonNull(deployment,
                "Deployment resource named " + deploymentName + " in namespace " + ns + " not found ");
        Map<String, String> deploymentLabels = deployment
                .getMetadata()
                .getLabels();

        Map<String, String> podLabels = ReUsableItems.subset(deploymentLabels,
                "website","environment","app","app.mpp.io/managed-by");

        List<Pod> pods = openShiftClient.pods().inNamespace(ns)
                .withLabels(podLabels)
                .list()
                .getItems();
        return pods.stream()
                .map(pod -> pod.getMetadata().getName())
                .toList();
    }


    void createOrReplaceBuild(KubernetesList buildConfig, String ns, String debugMessage) {
        var createdBc = openShiftClient.resourceList(buildConfig).inNamespace(ns)
                .createOrReplace();
        LOG.debug(debugMessage, createdBc);
    }

    Reader getTailingBuildLog(String buildName, String ns, int upto, boolean isRemoteBuild) {
        LOG.debug("Inside getTailingBuildLog");
        OpenShiftClient client = selectClient(isRemoteBuild,"fetching trailing build log");
        return client.builds().inNamespace(ns).withName(buildName)
                .tailingLines(upto).withPrettyOutput().getLogReader();
    }

    Reader getCompleteBuildLog(String buildName, String ns, boolean isRemoteBuild) {
        LOG.debug("Inside getCompleteBuildLog");
        OpenShiftClient client = selectClient(isRemoteBuild,"fetching build log");

        return client.builds().inNamespace(ns).withName(buildName).getLogReader();
    }

    public boolean hasBuildEnded(String buildName, String ns, boolean isRemoteBuild) {
        LOG.debug("Invoked hasBuildEnded");
        OpenShiftClient client = selectClient(isRemoteBuild,"checking build status");
        var phase = client.builds().inNamespace(ns).withName(buildName).get().getStatus().getPhase();
        return "Complete".equals(phase) || "Failed".equals(phase) || "Cancelled".equals(phase);
    }

    public Map<String,Object> fetchBuildMeta(String buildName, String ns, boolean isRemote) {
        LOG.debug("Invoked buildMeta");
        Map<String,Object> meta = new HashMap<>();
        OpenShiftClient client = selectClient(isRemote,"fetching build meta");
        try{
            var build = client.builds().inNamespace(ns).withName(buildName).get();
            var durationInNanos = build.getStatus().getDuration();
            meta.put("Phase",build.getStatus().getPhase());
            meta.put("Name",buildName);
            meta.put("NameSpace",ns);
            // 1 second = 1_000_000_000 nano seconds
            meta.put("DurationInSecs",(durationInNanos / 1_000_000_000));
        }catch(Exception ex){
            meta.put("Exception", ex.getMessage());
        }
        return meta;
    }

    public boolean isBuildSuccessful(String buildName, String ns, boolean isRemoteBuild){
        LOG.debug("Invoked isBuildSuccessful");
        OpenShiftClient client = selectClient(isRemoteBuild,"checking build status");
        var phase = client.builds().inNamespace(ns).withName(buildName).get().getStatus().getPhase();
        return "Complete".equals(phase);
    }

    public String checkBuildPhase(String buildName, String ns, boolean isRemoteBuild){
        LOG.debug("Invoked isBuildSuccessful");
        OpenShiftClient client = selectClient(isRemoteBuild,"checking build phase");
        var build = client.builds().inNamespace(ns).withName(buildName).get();
        if(Objects.isNull(build))
            return "NF";
        return build.getStatus().getPhase();
    }


    OpenShiftClient selectClient(boolean isRemote, String reason){
        OpenShiftClient client = null;
        if(isRemote){
            LOG.debug("remote build client selected for {}",reason);
            client = remoteBuildClient;
        }else{
            LOG.debug("local build client selected for {}",reason);
            client = openShiftClient;
        }
        return client;
    }

    private void throwExceptionWhenConditionNotMatched(boolean condition, String message) {
        if (!condition)
            throw new RuntimeException(message);
    }

    public void createOrUpdateRemoteBuildConfig(InputStream is,
                                                Map<String, String> templateParameterMap, String ns) {
        var buildConfig = remoteBuildClient
                .templates()
                .load(is)
                .processLocally(templateParameterMap);
        createOrReplaceRemoteBuild(buildConfig,ns,"Remote BuildConfig created successfully.");
    }

    void createOrReplaceRemoteBuild(KubernetesList buildConfig, String ns, String debugMessage) {
        var createdRemoteBc = remoteBuildClient.resourceList(buildConfig).inNamespace(ns)
                .createOrReplace();
        LOG.debug(debugMessage, createdRemoteBc);
    }


}
