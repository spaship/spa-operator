package io.spaship.operator.service.k8s;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.readiness.Readiness;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.spaship.operator.business.EventManager;
import io.spaship.operator.exception.ResourceNotFoundException;
import io.spaship.operator.service.Operations;
import io.spaship.operator.type.ApplicationConstants;
import io.spaship.operator.type.Environment;
import io.spaship.operator.type.EventStructure;
import io.spaship.operator.type.OperationResponse;
import io.spaship.operator.util.BuildConfigYamlModifier;
import io.spaship.operator.util.ReUsableItems;
import lombok.SneakyThrows;
import org.eclipse.microprofile.config.ConfigProvider;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

// todo this class violates single responsibility principal
@ApplicationScoped
public class Operator implements Operations {
    private static final Logger LOG = LoggerFactory.getLogger(Operator.class);
    private final OpenShiftClient ocClient;
    private final EventManager eventManager;
    private final String domain;
    private final String routerDomain;
    private final String routerShard;
    private final String appInstance;
    private final String deDebugNs;

    public Operator(@Named("default") OpenShiftClient ocClient,
                    EventManager eventManager, @Named("deNamespace") String ns) {
        this.ocClient = ocClient;
        this.eventManager = eventManager;
        domain = ConfigProvider.getConfig().getValue("operator.domain.name", String.class);
        routerDomain = ConfigProvider.getConfig().getValue("operator.router.domain.name", String.class);
        routerShard = ConfigProvider.getConfig().getValue("operator.router.shard.type", String.class);
        appInstance = setAppInstanceValue();
        this.deDebugNs = ns;
    }

    private String setAppInstanceValue() {
        var propertyValue = ConfigProvider.getConfig().getValue("app.instance", String.class);
        if (Objects.isNull(propertyValue) || propertyValue.isEmpty() || propertyValue.isBlank()) {
            LOG.warn("property app.instance is not set, going with the default value");
            propertyValue = "default";
        }
        return propertyValue;
    }

    public OperationResponse createOrUpdateEnvironment(Environment environment) {

        propertyValidation();

        ReUsableItems.enforceOpsLocking(new Pair<>(environment.getIdentification(), environment.getTraceID()));

        boolean envExists = environmentExists(environment);
        LOG.debug("envExists is {}", envExists);
        if (!envExists)
            createNewEnvironment(environment);
        String sideCarSvcUrl = environmentSidecarUrl(environment);
        // todo mutability is not good, we should not change the state of the object
        environment.setOperationPerformed(true);

        ReUsableItems.releaseLock(environment.getIdentification());
        LOG.debug("\n");
        // todo change the status code type to enum instead of int for better readability
        return OperationResponse.builder().environment(environment).sideCarServiceUrl(sideCarSvcUrl)
                .originatedFrom(this.getClass().toString()).status(envExists ? 2 : 1).build();
    }

    private void propertyValidation() {
        propertyValidation(domain, "operator.domain.name");
        propertyValidation(routerDomain, "operator.router.domain.name");
        propertyValidation(routerShard, "operator.router.shard.type");
    }

    private void propertyValidation(String property, String propertyID) {
        if (Objects.isNull(property)) {
            LOG.error("the property value of {} is missing", propertyID);
            throw new ResourceNotFoundException("property " + propertyID + " is not set");
        }
    }

    public boolean environmentExists(Environment environment) {
        if (!nameSpaceExists(environment))
            return false;
        return podExists(environment);
    }

    private boolean nameSpaceExists(Environment environment) {
        return nameSpaceExists(environment.getNameSpace());
    }

    private boolean nameSpaceExists(String namespace) {
        var ns = ocClient.namespaces().withName(namespace).get();
        var nsExists = Objects.nonNull(ns);
        LOG.debug("nameSpaceExists status is {}", nsExists);
        return nsExists;
    }

    private boolean podExists(Environment environment) {
        Map<String, String> labels = searchCriteriaLabel(environment);
        List<Pod> matchedPods = ocClient.pods().inNamespace(environment.getNameSpace()).withLabels(labels).list()
                .getItems();
        LOG.debug("{} no of matched pod found with search criteria {}", matchedPods.size(), labels);
        return !matchedPods.isEmpty();
    }


    public Uni<OperationResponse> deleteEnvironment(Environment environment) {

        return Uni.createFrom().item(buildK8sResourceList(environment))
                .emitOn(Infrastructure.getDefaultExecutor())
                .map(resourceList -> applyDeleteResourceList(environment, resourceList))
                .onFailure()
                .recoverWithItem(
                        throwable -> OperationResponse.builder().environment(environment)
                                .sideCarServiceUrl("NA").errorMessage(throwable.getMessage())
                                .originatedFrom(this.getClass().toString()).status(0).build());

    }

    void createNewEnvironment(Environment environment) {
        if (!nameSpaceExists(environment))
            createMpPlusProject(environment);
        KubernetesList result = buildK8sResourceList(environment);
        LOG.debug("create environment is in progress");
        processK8sList(result, environment.getTraceID(), environment.getNameSpace());
    }

    // TODO: this implementation is mp+ specific, using inheritance
    // create implementations for different cloud providers,
    // the implementation is determined by an attribute of application.properties
    private void createMpPlusProject(Environment environment) {
        Map<String, String> templateParameters = buildTemplateParameterMap(environment);
        LOG.debug("creating namespace with parameter {}", templateParameters);
        createNewTenantNamespace(environment, templateParameters);
        var eb = EventStructure.builder().uuid(environment.getTraceID().toString());
        eb.websiteName(environment.getWebsiteName()).environmentName(environment.getName()).state(
                "namespace created successfully in {} cluster".replace("{}", environment.getNameSpace()));
        prepareNewTenantNameSpace(environment, templateParameters, eb);
    }

    private Map<String, String> buildTemplateParameterMap(Environment environment) {

        var tenantName = ConfigProvider.getConfig().getValue("mpp.tenant.name", String.class);
        var ns = (environment.getNameSpace()).replace(tenantName.concat("--"), "");
        LOG.debug("Creating namespace with name {}", ns);
        var appCode = ConfigProvider.getConfig().getValue("mpp.app.code", String.class);

        var devOpsNamingConvention = ConfigProvider.getConfig()
                .getValue("application.devops.naming.convention", String.class);

        return Map.of("APP_CODE", appCode,
                "TENANT_NAME", tenantName,
                "NS_NAME", ns,
                "DEVOPS_NAMING_CONVENTION", devOpsNamingConvention,
                "DE_NAMESPACE", deDebugNs);
    }

    private void createNewTenantNamespace(Environment environment, Map<String, String> templateParameters) {
        var k8sNSList = ocClient
                .templates()
                .load(Operations.class.getResourceAsStream("/openshift/mpp-namespace-template.yaml"))
                .processLocally(templateParameters);
        ocClient.resourceList(k8sNSList).createOrReplace();
        LOG.debug("new namespace {} created successfully ", environment.getNameSpace());
    }

    @SneakyThrows
    private void prepareNewTenantNameSpace(Environment environment, Map<String, String> templateParameters,
                                           EventStructure.EventStructureBuilder eb) {
        var nsSupportResourcesList = ocClient
                .templates()
                .inNamespace(environment.getNameSpace())
                .load(Operations.class.getResourceAsStream("/openshift/mpp-prepare-namespace.yaml"))
                .processLocally(templateParameters);

        Uni.createFrom().item(nsSupportResourcesList)
                .map(item -> {
                    LOG.debug("executing mpp-prepare-namespace.yaml for namespace {}", environment.getNameSpace());
                    return ocClient.resourceList(item).inNamespace(environment.getNameSpace()).createOrReplace();
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
                .thenAccept(reactOnOperationOutcome(environment, eb)).get();
        updateTenantEgressForIAD2Cluster(environment.getNameSpace());
    }

    private void updateTenantEgressForIAD2Cluster(String namespace) {
        var domainName = ConfigProvider.getConfig().getValue("operator.domain.name", String.class);
        if (!domainName.contains("iad2")) {
            LOG.info("This namespace is not in iad2 cluster hence skipping the TenantEgress update process");
            return;
        }
        CustomResourceDefinitionContext crdContext = new CustomResourceDefinitionContext.Builder()
                .withGroup("tenant.paas.redhat.com")
                .withVersion("v1alpha1")
                .withScope("Namespaced")
                .withPlural("tenantegresses")
                .build();
        var resource = ocClient.genericKubernetesResources(crdContext)
                .inNamespace(namespace).withName("default");
        LOG.debug("TenantEgress resource is as follows {}", resource);
        var existingEgress = resource.get();
        LOG.debug("existingEgress value is as follows {}", existingEgress);
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
        LOG.debug("final egress length is {} ", egressRules.size());
        var patchOutput = resource.patch(existingEgress);
        LOG.debug("Egress patching output is {} ", patchOutput);
    }

    private Consumer<List<HasMetadata>> reactOnOperationOutcome(Environment environment,
                                                                EventStructure.EventStructureBuilder eb) {
        return param -> {
            if (Objects.isNull(param)) {
                LOG.error("failed to create the namespace after 10 attempts , check the stacktrace for more details");
                return;
            }
            eventManager.queue(eb.build());
            LOG.debug("network management and rbac policy installed successfully in namespace {} ",
                    environment.getNameSpace());
        };
    }

    public String environmentSidecarUrl(Environment environment) {
        String serviceName = "svc"
                .concat("-")
                .concat(appInstance)
                .concat("-")
                .concat(environment.getWebsiteName().toLowerCase())
                .concat("-")
                .concat(environment.getName().toLowerCase());
        LOG.debug("computed service name is {}", serviceName);
        var svc = ocClient.services().inNamespace(environment.getNameSpace()).withName(serviceName).get();

        String clusterIP = svc.getSpec().getClusterIP();
        var svcPort = svc.getSpec().getPorts().stream()
                .filter(port -> Objects.equals(port.getName(), "http-api"))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("service port not found"))
                .getPort();
        LOG.debug("computed service port is {}, clusterIp is {}", svcPort, clusterIP);

        String sideCarSvcUrl = "tcp://".concat(clusterIP).concat(":").concat(String.valueOf(svcPort));
        LOG.debug("computed sidecar service url is {}", sideCarSvcUrl);

        return sideCarSvcUrl;

    }

    Map<String, String> searchCriteriaLabel(Environment environment) {
        return Map.of(ApplicationConstants.MANAGED_BY, ApplicationConstants.SPASHIP,
                ApplicationConstants.WEBSITE, environment.getWebsiteName().toLowerCase(),
                ApplicationConstants.ENVIRONMENT, environment.getName().toLowerCase());
    }

    private OperationResponse applyDeleteResourceList(Environment environment, KubernetesList resourceList) {
        LOG.debug("applying delete on resources");
        // Should we delete the pvs as well? will that be a good idea?
        var isDeleted = ocClient.resourceList(resourceList).inNamespace(environment.getNameSpace()).delete();
        environment.setOperationPerformed(true);
        var or = OperationResponse.builder().environment(environment)
                .sideCarServiceUrl("NA")
                .originatedFrom(this.getClass().toString());
        or.status(3);
        if (isDeleted.isEmpty())
            or.status(0).errorMessage("unable to delete the resources");
        return or.build();
    }

    private KubernetesList buildK8sResourceList(Environment environment) {
        LOG.info("inide buildK8sResourceList the environment value is {}", environment);
        Map<String, String> templateParameters = Map.of(
                "WEBSITE", environment.getWebsiteName().toLowerCase(),
                // "TRACE_ID", environment.getTraceID().toString().toLowerCase(),
                "ENV", environment.getName().toLowerCase(),
                "WEBSITE_VERSION", environment.getWebsiteVersion().toLowerCase(),
                "DOMAIN", domain,
                "APP_INSTANCE_PREFIX", appInstance,
                "STORAGE_CLASS", ConfigProvider.getConfig().getValue("storage.class", String.class),
                "NS", environment.getNameSpace(),
                "SHARD", routerShard,
                "ROUTER_DOMAIN", routerDomain,
                "CMDB_CODE", environment.getCmdbCode()
        );
        LOG.debug("building KubernetesList, templateParameters are as follows {}", templateParameters);

        KubernetesList kubernetesList = null;
        try {
            kubernetesList = ocClient
                    .templates()
                    .inNamespace(environment.getNameSpace())
                    .load(Operator.class.getResourceAsStream("/openshift/environment-template.yaml"))
                    .processLocally(templateParameters);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        if (Objects.isNull(kubernetesList))
            throw new RuntimeException("Faild to locally process kubernetesList");

        return kubernetesList;
    }

    public boolean isEnvironmentAvailable(Environment environment) {
        boolean isAvailable = false;

        Objects.requireNonNull(environment.getName(), "environment name not fond in env object");
        Objects.requireNonNull(environment.getWebsiteName(), "website name not found in env object");
        Objects.requireNonNull(environment.getNameSpace(), "website namespace not found in env object");
        Map<String, String> labels = Map.of(
                ApplicationConstants.MANAGED_BY, ApplicationConstants.SPASHIP,
                ApplicationConstants.WEBSITE, environment.getWebsiteName(),
                ApplicationConstants.ENVIRONMENT, environment.getName());
        var pods = ocClient.pods()
                .inNamespace(environment.getNameSpace()).withLabels(labels).list().getItems();

        if (Objects.isNull(pods)) {
            LOG.warn("List<Pod> is null");
            return isAvailable;
        }
        if (pods.isEmpty()) {
            LOG.info("no pod found with the following details {}", labels);
            return isAvailable;
        }
        if (pods.size() > 1)
            LOG.warn("more than one pod found with the same criteria");

        var pod0 = pods.get(0);
        var phase = pod0.getStatus().getPhase();
        var readiness = Readiness.isPodReady(pod0);
        LOG.debug("pod phase is {} ", phase);
        return phase.equalsIgnoreCase("Running") && readiness;

    }

    // TODO remove if blocks
    private void processK8sList(KubernetesList result, UUID tracing, String nameSpace) {

        var eb = EventStructure.builder().uuid(tracing.toString());

        result.getItems().forEach(item -> {
            if (item instanceof Service svc) {
                LOG.debug("creating new Service in K8s, tracing = {}", tracing);
                ocClient.services().inNamespace(nameSpace).createOrReplace(svc);
                eb.websiteName(item.getMetadata().getLabels().get(ApplicationConstants.WEBSITE))
                        .environmentName(item.getMetadata().getLabels().get(ApplicationConstants.ENVIRONMENT))
                        .state("service created");
            }
            if (item instanceof Deployment dep) {
                LOG.debug("creating new Deployment in K8s, tracing = {}", tracing);
                ocClient.apps().deployments().inNamespace(nameSpace).resource(dep).createOrReplace();
                eb.websiteName(item.getMetadata().getLabels().get(ApplicationConstants.WEBSITE))
                        .environmentName(item.getMetadata().getLabels().get(ApplicationConstants.ENVIRONMENT))
                        .state("deployment created");
            }
            if (item instanceof StatefulSet sfs) {
                LOG.debug("creating new Deployment in K8s, tracing = {}", tracing);
                ocClient.apps().statefulSets().inNamespace(nameSpace).resource(sfs).createOrReplace();
                eb.websiteName(item.getMetadata().getLabels().get(ApplicationConstants.WEBSITE))
                        .environmentName(item.getMetadata().getLabels().get(ApplicationConstants.ENVIRONMENT))
                        .state("StatefulSet created");

            }
            if (item instanceof PersistentVolumeClaim pvc) {
                LOG.debug("creating new pvc in K8s, tracing = {}", tracing);
                ocClient.persistentVolumeClaims().inNamespace(nameSpace).resource(pvc).createOrReplace();
                eb.websiteName(item.getMetadata().getLabels().get(ApplicationConstants.WEBSITE))
                        .environmentName(item.getMetadata().getLabels().get(ApplicationConstants.ENVIRONMENT))
                        .state("pvc created");
            }
            if (item instanceof Route route) {
                LOG.debug("creating new Route in K8s, tracing = {}", tracing);
                ocClient.routes().inNamespace(nameSpace).resource(route).createOrReplace();
                eb.websiteName(item.getMetadata().getLabels().get(ApplicationConstants.WEBSITE))
                        .environmentName(item.getMetadata().getLabels().get(ApplicationConstants.ENVIRONMENT))
                        .state("route created");
            }
            if (item instanceof ConfigMap cm) {
                LOG.debug("creating new ConfigMap in K8s, tracing = {}", tracing);
                ocClient.configMaps().inNamespace(nameSpace).resource(cm).createOrReplace();
                eb.websiteName(item.getMetadata().getLabels().get(ApplicationConstants.WEBSITE))
                        .environmentName(item.getMetadata().getLabels().get(ApplicationConstants.ENVIRONMENT))
                        .state("configmap created");
            }
            if (item instanceof Ingress ing) {
                LOG.debug("creating new Ingress controller in K8s, tracing = {}", tracing);
                ocClient.network().v1().ingresses().inNamespace(nameSpace).resource(ing).createOrReplace();
            }
            if(item instanceof HorizontalPodAutoscaler hpa) {
                LOG.debug("creating new HorizontalPodAutoscaler in K8s, tracing = {}", tracing);
                ocClient.autoscaling().v2().horizontalPodAutoscalers().inNamespace(nameSpace).resource(hpa)
                        .createOrReplace();
            }

            eventManager.queue(eb.build());
        });

    }

    public ConfigMap updateConfigMap(Environment environment, Object syncConfig) {

        var configMapName = "sidecar-config-"
                .concat(environment.getWebsiteName().toLowerCase())
                .concat("-")
                .concat(environment.getName().toLowerCase());
        var cfgMapData = ocClient.configMaps().inNamespace(environment.getNameSpace()).withName(configMapName).get();
        var existingConfigData = cfgMapData.getData();
        existingConfigData.put("SIDECAR_SYNC_CONFIG", syncConfig.toString());
        cfgMapData.setData(existingConfigData);
        return ocClient.configMaps().inNamespace(environment.getNameSpace()).createOrReplace(cfgMapData);
    }
}
