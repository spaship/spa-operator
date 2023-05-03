package io.spaship.operator.type;


import io.spaship.operator.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record GitFlowMeta(SsrResourceDetails deploymentDetails, String gitRef, String repoUrl, String contextDir,
                          List<Map<String, String>> buildArgs, String nameSpace,
                          boolean reDeployment, String buildName) {

    private static final Logger LOG = LoggerFactory.getLogger(GitFlowMeta.class);

    public Map<String, String> toTemplateParameterMap() {
        Map<String, String> params = new HashMap<>();
        if (Objects.nonNull(deploymentDetails) &&
                Objects.nonNull(deploymentDetails.website()) &&
                Objects.nonNull(deploymentDetails.app()))
            params.put("NAME", buildConfigName());
        if (Objects.nonNull(gitRef))
            params.put("GIT_REF", gitRef);
        if (Objects.nonNull(repoUrl))
            params.put("GIT_URI", repoUrl);
        if (Objects.nonNull(contextDir) && !StringUtil.containsOnlyForwardSlash(contextDir))
            params.put("CONTEXT_DIR", contextDir);
        if (Objects.nonNull(deploymentDetails) &&
                Objects.nonNull(deploymentDetails.website()) &&
                Objects.nonNull(deploymentDetails.app()) &&
                Objects.nonNull(deploymentDetails.environment()))
            params.put("OUTPUT_NAME", completeImageRepoUrl());
        return params;
    }

    public Map<String, String> toIsTemplateParameterMap() {
        Map<String, String> params = new HashMap<>();
        if (Objects.nonNull(deploymentDetails) &&
                Objects.nonNull(deploymentDetails.website()) &&
                Objects.nonNull(deploymentDetails.app()))
            params.put("NAME", imageStreamName());
        return params;
    }

    private String completeImageRepoUrl() {
        if (Objects.nonNull(deploymentDetails) && Objects.nonNull(deploymentDetails.website()) && Objects.nonNull(deploymentDetails.app()) &&
                Objects.nonNull(deploymentDetails.environment())) {
            var constructedImageUrl = deploymentDetails().website()
                    .concat("-")
                    .concat(deploymentDetails().app())
                    .concat(":")
                    .concat(deploymentDetails().environment());
            LOG.debug("constructed container image url is {}", constructedImageUrl);
            return constructedImageUrl;
        }
        throw new RuntimeException("Some required details are missing");
    }

    public String imageStreamName() {
        return deploymentDetails()
                .website().concat("-")
                .concat(deploymentDetails().app());
    }

    public String buildConfigName() {
        return deploymentDetails()
                .website().concat("-")
                .concat(deploymentDetails().app())
                .concat("-")
                .concat(deploymentDetails().environment());
    }


    public String deploymentName() {
        return deploymentDetails()
                .website().concat("-")
                .concat(deploymentDetails().app()).concat("-").concat(deploymentDetails.environment());
    }

    private SsrResourceDetails constructNewDeploymentResource() {
        return new SsrResourceDetails(nameSpace,
                completeImageRepoUrl(), deploymentDetails.app(), deploymentDetails.contextPath(),
                deploymentDetails.healthCheckPath(), deploymentDetails.website(),
                deploymentDetails.environment(), deploymentDetails.port(), deploymentDetails.configMap());
    }

    public GitFlowMeta newGitFlowMetaWithBuildName(String buildName) {
        return new GitFlowMeta(
                this.constructNewDeploymentResource(),
                this.gitRef,
                this.repoUrl,
                this.contextDir,
                this.buildArgs,
                this.nameSpace,
                this.reDeployment,
                buildName
        );
    }


    public BuildType typeOfBuild() {
        boolean isMono = (Objects.nonNull(contextDir) && !StringUtil.containsOnlyForwardSlash(contextDir));
        boolean hasBuildArg = Objects.nonNull(buildArgs) && !buildArgs.isEmpty();

        if (isMono && hasBuildArg)
            return BuildType.MONO_WITH_BUILD_ARG;
        if (isMono)
            return BuildType.MONO;
        if (hasBuildArg)
            return BuildType.WITH_BUILD_ARG;

        return BuildType.REGULAR;
    }

    public enum BuildType {
        REGULAR, MONO, WITH_BUILD_ARG, MONO_WITH_BUILD_ARG
    }

}
