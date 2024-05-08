package io.spaship.operator.config;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.openshift.client.OpenShiftClient;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import io.quarkus.runtime.configuration.ProfileManager;
import io.spaship.operator.exception.ResourceNotFoundException;


@Singleton
public class K8sClientProducer {
  private static final Logger LOG = LoggerFactory.getLogger(K8sClientProducer.class);

  @ConfigProperty(name = "mpp.de.default.namespace")
  Optional<String> nameSpaceFromEnv;

  @ConfigProperty(name = "application.webprop.ns.default")
  Optional<String> defaultWebappNameSpace;

  @Produces
  @Named("deNamespace")
  String getNamespace() {
    String computedNameSpace = Optional.ofNullable(readNameFromFile()).orElseGet(this::whenNameSpaceMetaFileNotFound);
    LOG.info("computed deNamespace namespace is {}", computedNameSpace);
    return computedNameSpace;
  }
  @Produces
  @Named("defaultNamespaceMT")
  String getMultiTenantDefaultNamespace() {
    var computedNamespace = multiTenantDeploymentDefaultNs();
    LOG.info("computed defaultNamespaceMT  namespace is {}", computedNamespace);
    return computedNamespace;
  }

  private String readNameFromFile() {
    LOG.info("looking for namespace into the predefined location");
    String ns;
    try {
      ns = new String(Files
        .readAllBytes(Paths.get("/var/run/secrets/kubernetes.io/serviceaccount/namespace")));
      LOG.info("content of the namespace is {}", ns);
    } catch (IOException e) {
      LOG.error("failed to read namespace from metadata {}", e.getMessage());
      ns = null;
    }
    LOG.info("returning namespace from metadata {}", ns);
    return ns;
  }

  // This implementation is mpp specific
  private String whenNameSpaceMetaFileNotFound() {

    LOG.info("namespace file does not exists.");

    if(!ProfileManager.getActiveProfile().toLowerCase().contains("dev"))
      throw new ResourceNotFoundException("the profile is not dev , could not determine de namespace");

    return nameSpaceFromEnv.orElseThrow(() -> {
      throw new ResourceNotFoundException("deployment engine's default namespace is not set in properties file");
    });
  }

  public String multiTenantDeploymentDefaultNs(){
    return defaultWebappNameSpace.orElseGet(() -> {
      LOG.debug("namespace not on environment either! proceeding with ns <default-ns-not-found>");
      return "default-ns-not-found";
    });
  }

  @Produces
  @Named("default")
  public OpenShiftClient  openshiftClient() {
    final KubernetesClientBuilder kubernetesClientBuilder = new KubernetesClientBuilder();
    final ConfigBuilder configBuilder = new ConfigBuilder();


    if (!ProfileManager.getActiveProfile().toLowerCase().contains("local")) {
      configBuilder.withOauthToken(ConfigProvider.getConfig().getValue("mpp.cluster.access.token", String.class));
    }

    configBuilder.withTrustCerts(true)
      .withConnectionTimeout(600000)
      .withRequestTimeout(600000)
      .withWebsocketPingInterval(600000)
      .withUploadConnectionTimeout(600000)
      .withUploadRequestTimeout(600000);
    return kubernetesClientBuilder.withConfig(configBuilder.build()).build().adapt(OpenShiftClient.class);
  }

  @Produces
  @Named("build")
  public OpenShiftClient  buildClient() {
    final KubernetesClientBuilder kubernetesClientBuilder = new KubernetesClientBuilder();
    final ConfigBuilder configBuilder = new ConfigBuilder();

    if (!ProfileManager.getActiveProfile().toLowerCase().contains("local")) {
      configBuilder.withOauthToken(ConfigProvider.getConfig()
              .getValue("mpp.remote.build.cluster.access.token", String.class));
    }

    configBuilder.withTrustCerts(true)
            .withMasterUrl(ConfigProvider.getConfig().getValue("mpp.remote.build.cluster.master.url", String.class))
            .withConnectionTimeout(600000)
            .withRequestTimeout(600000)
            .withWebsocketPingInterval(600000)
            .withUploadConnectionTimeout(600000)
            .withUploadRequestTimeout(600000);
    return kubernetesClientBuilder
            .withConfig(configBuilder.build()).build().adapt(OpenShiftClient.class);
  }

}
