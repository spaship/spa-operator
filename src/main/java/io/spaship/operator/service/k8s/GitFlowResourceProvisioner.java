package io.spaship.operator.service.k8s;

import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.openshift.api.model.BuildRequest;
import io.fabric8.openshift.api.model.BuildRequestBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.spaship.operator.type.K8sResourceLog;
import io.spaship.operator.util.ReUsableItems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.io.Reader;
import java.util.*;

@ApplicationScoped
//todo extract all Logs related functions to another class, separate the concerns
public class GitFlowResourceProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(GitFlowResourceProvisioner.class);
    private static final String IMAGE_STREAM_TEMPLATE_LOCATION = "/openshift/is-template.yaml";
    private static final String BUILD_CONFIG_TEMPLATE_LOCATION = "/openshift/build-template.yaml";
    private final OpenShiftClient openShiftClient;

    public GitFlowResourceProvisioner(OpenShiftClient openShiftClient) {
        this.openShiftClient = openShiftClient;
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


    public String triggerBuild(String buildConfigName, String ns) {

        BuildRequest buildRequest = new BuildRequestBuilder()
                .withNewMetadata()
                .withName(buildConfigName)
                .endMetadata()
                .build();
        var buildConfig = openShiftClient.buildConfigs().inNamespace(ns).withName(buildConfigName).isReady();
        throwExceptionWhenConditionNotMatched(buildConfig,
                "BuildConfig resource named " + buildConfigName + " in namespace " + ns + " not found ");
        var build = openShiftClient.buildConfigs().inNamespace(ns).withName(buildConfigName).instantiate(buildRequest);
        Objects.requireNonNull(build,
                "something went wrong with build created from " + buildConfigName + " in namespace " + ns);
        return build.getMetadata().getName();
    }


    public Reader getBuildLog(String buildName, String ns, int upto) {
        LOG.debug("getBuildLog called with buildName: {}; namespace: {}; upto: {}", buildName, ns, upto);
        if (upto <= 0)
            return getCompleteBuildLog(buildName, ns);
        return getTailingBuildLog(buildName, ns, upto);
    }

    public boolean deploymentIsReady(String deploymentName, String ns) {
        LOG.debug("Invoked method deploymentReadiness with deploymentName: {}, in Namespace: {}", deploymentName, ns);
        var deployment = openShiftClient.apps().deployments().inNamespace(ns).withName(deploymentName).get();
        var listOfConditions = deployment.getStatus().getConditions();
        var condition = listOfConditions.stream().filter(c-> "Available".equals(c.getType()))
                .findFirst().orElse(null);
        return Objects.nonNull(condition) && "True".equals(condition.getStatus());
    }

    // TODO simplify this method, use function composition style or break into sub methods
    public List<K8sResourceLog> getTailingDeploymentLog(String deploymentName, String ns, int upto) {
        LOG.debug("Invoked method getTailingDeploymentLog with deploymentName: {}, in Namespace: {}, upto: {} "
                , deploymentName, ns, upto);
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
        List<K8sResourceLog> podLogs = new ArrayList<>();
        for (Pod pod : pods) {
            var podName = pod.getMetadata().getName();
            var logs = openShiftClient.pods().inNamespace(ns)
                    .withName(podName).tailingLines(upto).withPrettyOutput().getLog(true);
            podLogs.add(new K8sResourceLog(podName, K8sResourceLog.Source.POD, logs));
        }
        return podLogs;
    }


    void createOrReplaceBuild(KubernetesList buildConfig, String ns, String debugMessage) {
        var createdBc = openShiftClient.resourceList(buildConfig).inNamespace(ns)
                .createOrReplace();
        LOG.debug(debugMessage, createdBc);
    }

    Reader getTailingBuildLog(String buildName, String ns, int upto) {
        LOG.debug("Inside getTailingBuildLog");
        return openShiftClient.builds().inNamespace(ns).withName(buildName)
                .tailingLines(upto).withPrettyOutput().getLogReader();
    }

    Reader getCompleteBuildLog(String buildName, String ns) {
        LOG.debug("Inside getCompleteBuildLog");
        return openShiftClient.builds().inNamespace(ns).withName(buildName).getLogReader();
    }

    public boolean hasBuildEnded(String buildName, String ns) {
        LOG.debug("Invoked hasBuildEnded");
        var phase = openShiftClient.builds().inNamespace(ns).withName(buildName).get().getStatus().getPhase();
        return "Complete".equals(phase) || "Failed".equals(phase) || "Cancelled".equals(phase);
    }

    public boolean isBuildSuccessful(String buildName, String ns){
        LOG.debug("Invoked isBuildSuccessful");
        var phase = openShiftClient.builds().inNamespace(ns).withName(buildName).get().getStatus().getPhase();
        return "Complete".equals(phase);
    }

    public String checkBuildPhase(String buildName, String ns){
        LOG.debug("Invoked isBuildSuccessful");
        var build = openShiftClient.builds().inNamespace(ns).withName(buildName).get();
        if(Objects.isNull(build))
            return "NF";
        return build.getStatus().getPhase();
    }




    private void throwExceptionWhenConditionNotMatched(boolean condition, String message) {
        if (!condition)
            throw new RuntimeException(message);
    }
}