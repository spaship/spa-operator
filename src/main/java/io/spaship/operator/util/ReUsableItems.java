package io.spaship.operator.util;

import io.spaship.operator.repo.SharedRepository;
import lombok.SneakyThrows;
import org.eclipse.microprofile.config.ConfigProvider;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class ReUsableItems {
  private static final String SPASHIP_MAPPING_FILE = ".spaship";
  private static final Logger LOG = LoggerFactory.getLogger(ReUsableItems.class);


  private ReUsableItems() {
  }

  @SneakyThrows
  public static void blockFor(int timeInMs) {
    Thread.sleep(timeInMs);
  }


  public static String getSpashipMappingFileName() {
    return SPASHIP_MAPPING_FILE;
  }


  //lock environment creation operation for same website
  public static void enforceOpsLocking(Pair<String, UUID> blockDecisionFactors) {
    while (blockCall(blockDecisionFactors)) {
      LOG.debug("An environment creation/modification is in progress for this website {}",
        blockDecisionFactors.getValue0());
      ReUsableItems.blockFor(800);
    }
    SharedRepository.enqueue(blockDecisionFactors.getValue0(),
      new Pair<>(blockDecisionFactors.getValue1(), LocalDateTime.now()));
  }

  public static void releaseLock(String environmentName) {
    SharedRepository.dequeue(environmentName);
  }

  static boolean blockCall(Pair<String, UUID> decisionFactors) {
    String environmentId = decisionFactors.getValue0();
    UUID traceId = decisionFactors.getValue1();
    Pair<UUID, LocalDateTime> mapValue = SharedRepository.getEnvironmentLockMeta(environmentId);
    if (Objects.isNull(mapValue)) {
      LOG.warn("environmentLock not found!");
      return false;
    }
    LOG.debug("comparing {} with {}", decisionFactors, mapValue);

    return !SharedRepository.isQueued(environmentId)
      || !mapValue.getValue0().equals(traceId);
  }

  public static <K, V> Map<K, V> subset(Map<K, V> map, K... keys) {
    Map<K, V> subset = new HashMap<>();
    for (K key : keys) {
      if (map.containsKey(key)) {
        subset.put(key, map.get(key));
      }
    }
    return subset;
  }

  public static boolean isRemoteBuild(){
    return ConfigProvider
            .getConfig()
            .getOptionalValue("mpp.remote.build", Boolean.class)
            .orElse(false);
  }

  public static String remoteBuildNameSpace(){
    return ConfigProvider.getConfig().getValue("mpp.remote.build.ns", String.class);
  }

  public static String remoteBuildImageRepoSecretName() {
    return ConfigProvider.getConfig()
            .getValue("mpp.remote.build.repository.access.secret", String.class);
  }

  public static String remoteBuildImagePullSecret() {
    return ConfigProvider.getConfig()
            .getValue("mpp.remote.build.imagepull.secret", String.class);
  }

  public static String selectContainerizedDeploymentOCTemplate() {
    if (isRemoteBuild())
      return "/openshift/ssr-deployment-rb-template.yaml";
    return "/openshift/ssr-deployment-template.yaml";
  }


  @SafeVarargs
  public static <T> void checkNull(T... objects) {
    if (Objects.isNull(objects)) {
      throw new NullPointerException("The objects parameter itself is null");
    }
    for (int i = 0; i < objects.length; i++) {
      T entry = objects[i];
      if (Objects.isNull(entry)) {
        throw new NullPointerException("Object at index " + i + " is null");
      }
    }
  }

}
