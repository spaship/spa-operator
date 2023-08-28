package io.spaship.operator.service.k8s;

import io.fabric8.kubernetes.api.model.HasMetadata;

import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.openshift.client.OpenShiftClient;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple3;
import io.spaship.operator.exception.SsrException;
import io.spaship.operator.util.BuildConfigYamlModifier;
import io.spaship.operator.util.ReUsableItems;
import lombok.SneakyThrows;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import java.io.InputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import java.util.function.Consumer;
import org.eclipse.microprofile.config.ConfigProvider;

@ApplicationScoped
public class SsrResourceProvisioner {

    private static final String DEPLOYMENT_TEMPLATE_LOCATION = ReUsableItems.selectContainerizedDeploymentOCTemplate();
    private static final String NEW_NS_TEMPLATE = "/openshift/mpp-namespace-template.yaml";
    private static final String NS_NETWORK_POLICY_TEMPLATE = "/openshift/mpp-prepare-namespace.yaml";
    private static final String CONTAINER_NAME = "app-container";

    /*
     * TODO
     * Maintain the separation of concerns, By creating a wrapper class that wraps
     * these methods and accept
     * A tracing id then handle it internally so that these class can be reused and
     * none
     * of them pollutes these methods.
     * Have to add the create-ns and prepare-ns capabilities.
     * Wrapper class must not block the main thread,which is linked with API
     * Login feature using multiple methods
     * Integration with repository as a third party application
     * if context path is set but health check path does not contain context path
     * then add context path in health check path
     */

    private static final Logger LOG = LoggerFactory.getLogger(SsrResourceProvisioner.class);

    private final OpenShiftClient client;
    private final String deNameSpace;

    public SsrResourceProvisioner(@Named("default")OpenShiftClient client, @Named("deNamespace") String ns) {
        this.client = client;
        this.deNameSpace = ns;
    }

    public boolean createNewEnvironment(InputStream is, Map<String, String> templateParam, String nameSpace) {
        if(Objects.isNull(is)){
            is = SsrResourceProvisioner.class.getResourceAsStream(DEPLOYMENT_TEMPLATE_LOCATION);
        }
        var environmentResourceObject = client
                .templates()
                .load(is)
                .processLocally(templateParam);
        var outcome = client.resourceList(environmentResourceObject).inNamespace(nameSpace(nameSpace))
                .createOrReplace();
        return Objects.nonNull(outcome);
    }

    public boolean deleteExistingEnvironment(Map<String, String> templateParam, String nameSpace) {
        var environmentResourceObject = client
                .templates()
                .load(SsrResourceProvisioner.class.getResourceAsStream(DEPLOYMENT_TEMPLATE_LOCATION))
                .processLocally(templateParam);
        var outcome = client.resourceList(environmentResourceObject).inNamespace(nameSpace(nameSpace)).delete();
        LOG.info("outcome  {}", outcome);
        LOG.info("environmentResourceObject  {}", environmentResourceObject);
        return Objects.nonNull(outcome);
    }

    public void updateEnvironment(String deploymentName, String imageUrl, String nameSpace) {

        LOG.info("control reached in provisioner with the following details  {}, {}, {}", deploymentName, imageUrl,
                nameSpace);

        var deploymentResource = client.apps()
                .deployments()
                .inNamespace(nameSpace(nameSpace))
                .withName(deploymentName);
        var containers = deploymentResource
                .get()
                .getSpec()
                .getTemplate()
                .getSpec()
                .getContainers();
        containers.forEach(container -> {
            if (container.getName().equalsIgnoreCase(CONTAINER_NAME)) {
                LOG.info("replacing image url {} with new url {}", container.getImage(), imageUrl);
                container.setImage(imageUrl);
            }
        });
        LOG.info("replacement ops completed successfully");
        deploymentResource.rolling().updateImage(imageUrl);
        LOG.info("rolling is done");
    }

    public boolean updateConfigMapOf(Tuple3<String, String, String> labelValues, Map<String, String> configValues,
            String nameSpace) {

        var resourceName = labelValues.getItem1()
                .concat("-").concat(labelValues.getItem2()).concat("-")
                .concat(labelValues.getItem3());
        var configMapResource = client.configMaps()
                .inNamespace(nameSpace(nameSpace)).withName(resourceName);
        var configMap = configMapResource.get();

        LOG.info("Found configMap with name  {}", configMap.getMetadata().getName());

        var configMapData = configMap.getData();
        configMapData.putAll(configValues);

        LOG.info("New Configmap data is {}", configMapData);

        configMap.setData(configMapData);

        var outcome = configMapResource.patch(configMap);

        var delPodStatus = deletePod(labelValues, nameSpace);

        return Objects.nonNull(outcome) && delPodStatus;
    }

    public boolean updateSecretOf(Tuple3<String, String, String> labelValues, Map<String, String> secretValues,
            String nameSpace) {

        var resourceName = labelValues.getItem1()
                .concat("-").concat(labelValues.getItem2()).concat("-")
                .concat(labelValues.getItem3()).concat("-app-sec");
        var secretResource = client.secrets()
                .inNamespace(nameSpace(nameSpace)).withName(resourceName);
        var secret = secretResource.get();

        LOG.info("Found Secret with name  {}", secret.getMetadata().getName());

        var secretData = secret.getData();

        // Encoding all values to base64 before updating the hashmap is mandatory
        secretValues.replaceAll((key, value) -> Base64.getEncoder().encodeToString(value.getBytes()));

        secretData.putAll(secretValues);

        secret.setData(secretData);

        var outcome = secretResource.patch(secret);

        var delPodStatus = deletePod(labelValues, nameSpace);

        return Objects.nonNull(outcome) && delPodStatus;
    }

    private boolean deletePod(Tuple3<String, String, String> labelValues, String nameSpace) {
        var labels = Map.of(
                "website", labelValues.getItem1(),
                "app", labelValues.getItem2(),
                "environment", labelValues.getItem3());

        var pod = client.pods()
                .inNamespace(nameSpace(nameSpace))
                .withLabels(labels);
        var status = pod.delete();
        return Objects.nonNull(status);
    }

    public String nameSpace(String incomingNameSpace) {
        if (Objects.isNull(incomingNameSpace))
            throw new SsrException("namespace not found!");
        createMpPlusProject(incomingNameSpace);
        return incomingNameSpace;
    }

    // TODO move these methods to a seperate class and use as acommon functionality
    private void createMpPlusProject(String namespace) {
        if (nameSpaceExists(namespace)){
            LOG.debug("namespace {} already exists",namespace);
            return;
        }
        Map<String, String> templateParameters = buildTemplateParameterMap(namespace);
        LOG.debug("creating Project with the following information {}",templateParameters);
        createNewTenantNamespace(namespace, templateParameters);
        prepareNewTenantNameSpace(namespace, templateParameters);
    }

    private void createNewTenantNamespace(String namespace, Map<String, String> templateParameters) {
        var k8sNSList = client
                .templates()
                .load(SsrResourceProvisioner.class.getResourceAsStream(NEW_NS_TEMPLATE))
                .processLocally(templateParameters);
        client.resourceList(k8sNSList).createOrReplace();
        LOG.debug("new namespace {} created successfully ", namespace);
    }

    private boolean nameSpaceExists(String namespace) {
        var ns = client.namespaces().withName(namespace).get();
        var nsExists = Objects.nonNull(ns);
        LOG.debug("nameSpaceExists status is {}", nsExists);
        return nsExists;
    }

    @SneakyThrows
    private void prepareNewTenantNameSpace(String namespace, Map<String, String> templateParameters) {
        var nsSupportResourcesList = client
                .templates()
                .inNamespace(namespace)
                .load(SsrResourceProvisioner.class.getResourceAsStream(NS_NETWORK_POLICY_TEMPLATE))
                .processLocally(templateParameters);

        Uni.createFrom().item(nsSupportResourcesList)
                .map(item -> {
                    LOG.debug("executing mpp-prepare-namespace.yaml for namespace {}", namespace);
                    return client.resourceList(item).inNamespace(namespace).createOrReplace();
                })
                .onFailure()
                .retry()
                .withBackOff(Duration.ofSeconds(1), Duration.ofSeconds(2))
                .atMost(10)
                .onFailure()
                .recoverWithItem(throwable -> {
                    throwable.printStackTrace();
                    return null;
                }).subscribeAsCompletionStage()
                .thenAccept(reactOnOperationOutcome(namespace)).get();

        updateTenantEgressForIAD2Cluster(namespace);
    }

    // TODO refactor this method
    private void updateTenantEgressForIAD2Cluster(String namespace) {
        var domainName = ConfigProvider.getConfig().getValue("operator.domain.name", String.class);
        if(!domainName.contains("iad2")){
            LOG.info("This namespace is not in iad2 cluster hence skipping the TenantEgress update process");
            return;
        }
        CustomResourceDefinitionContext crdContext = new CustomResourceDefinitionContext.Builder()
                .withGroup("tenant.paas.redhat.com")
                .withVersion("v1alpha1")
                .withScope("Namespaced")
                .withPlural("tenantegresses")
                .build();
        var resource = client.genericKubernetesResources(crdContext)
                .inNamespace(namespace).withName("default");
        var existingEgress = resource.get();

        Objects.requireNonNull(existingEgress);
        var exceptionList = BuildConfigYamlModifier.extractEgressFromTemplate();
        Objects.requireNonNull(exceptionList);
        Map<String, Object> spec = (Map<String, Object>) existingEgress.getAdditionalProperties().get("spec");
        List<Map<String, Object>> egressRules = (List<Map<String, Object>>) spec.get("egress");
        egressRules.addAll(exceptionList);
        egressRules.removeIf(rule -> {
            String type = (String) rule.get("type");
            Map<String, Object> to = (Map<String, Object>) rule.get("to");
            String cidrSelectorName = (String) to.get("cidrSelector");
            return "0.0.0.0/0".equals(cidrSelectorName) && type.equalsIgnoreCase("Deny");
        });
        LOG.debug("final egress length is {} ",egressRules.size());
        var patchOutput = resource.patch(existingEgress);
        LOG.debug("Egress patching output is {} ",patchOutput);
    }

    private Consumer<List<HasMetadata>> reactOnOperationOutcome(String namespace) {
        return param -> {
            if (Objects.isNull(param)) {
                LOG.error("failed to create the namespace after 10 attempts , check the stacktrace for more details");
                return;
            }

            LOG.debug("network management and rbac policy installed successfully in namespace {} ",
                    namespace);
        };
    }

    private Map<String, String> buildTemplateParameterMap(String namespace) {
        var ns = namespace.replace("spaship--", "").replace("spaship-sandbox--", "");
        LOG.debug("Creating namespace with name {}", ns);
        var appCode = ConfigProvider.getConfig().getValue("mpp.app.code", String.class);
        var tenantName = ConfigProvider.getConfig().getValue("mpp.tenant.name", String.class);
        var devOpsNamingConvention = ConfigProvider.getConfig()
                .getValue("application.devops.naming.convention", String.class);

        return Map.of("APP_CODE", appCode,
                "TENANT_NAME", tenantName,
                "NS_NAME", ns,
                "DEVOPS_NAMING_CONVENTION", devOpsNamingConvention,
                "DE_NAMESPACE", deNameSpace);
    }

}
