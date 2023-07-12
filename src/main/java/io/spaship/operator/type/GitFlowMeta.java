package io.spaship.operator.type;


import io.spaship.operator.util.ReUsableItems;
import io.spaship.operator.util.StringUtil;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;




public record GitFlowMeta(SsrResourceDetails deploymentDetails, String gitRef, String repoUrl, String contextDir,
                          List<Map<String, String>> buildArgs, String nameSpace,
                          boolean reDeployment, String buildName, String dockerFilePath) {
// TODO introduce a new variable called remoteBuild to control the build from incoming payload
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
            params.put("OUTPUT_NAME", buildOutputLocation());
        if(Objects.nonNull(dockerFilePath) && !StringUtil.equalsDockerfile(dockerFilePath))
            params.put("DOCKER_FILE_PATH", dockerFilePath);

        /*
         * Regardless of whether it is a remote or local build, these parameters will be included.
         * This provision allows for future flexibility in case the following details need to be configured
         * from the front-end or middleware. In such a scenario,
         * three new members will need to be introduced in this class.
         **/
        params.put("REPOSITORY_URL", ConfigProvider.getConfig()
                .getValue("mpp.remote.build.repository.url", String.class));
        params.put("IMAGE_TAG",deploymentDetails.
                website().concat(".")
                .concat(deploymentDetails.app()).concat(".")
                .concat(deploymentDetails.environment()) );
        params.put("IMAGE_PUSH_SECRET", ReUsableItems.remoteBuildImageRepoSecretName());

        return params;
    }

    public String completeRemoteImageRepoUrl(){
        var baseUrl = ConfigProvider.getConfig()
                .getValue("mpp.remote.build.repository.url", String.class);
        var imageTag = deploymentDetails.
                website().concat(".")
                .concat(deploymentDetails.app()).concat(".")
                .concat(deploymentDetails.environment());
        boolean isRemoteBuild = ReUsableItems.isRemoteBuild();
        if(!isRemoteBuild || Objects.isNull(baseUrl))
            throw new RuntimeException("`mpp.remote.build.repository.url` not found or remote build is disabled");
        var imageUrl = baseUrl.concat(":").concat(imageTag);
        LOG.info("constructed remote repository url is {}",imageUrl);
        return imageUrl;
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
        if (Objects.nonNull(deploymentDetails) && Objects.nonNull(deploymentDetails.website())
                && Objects.nonNull(deploymentDetails.app()) &&
                Objects.nonNull(deploymentDetails.environment())) {
            var repositoryBaseUrl = ConfigProvider.getConfig().getValue("mpp.is.repository.base.url", String.class);
            var constructedImageUrl = repositoryBaseUrl.concat("/").concat(nameSpace()).concat("/")
                    .concat(deploymentDetails().website())
                    .concat("-")
                    .concat(deploymentDetails().app())
                    .concat(":")
                    .concat(deploymentDetails().environment());
            LOG.debug("constructed container image url is {}", constructedImageUrl);
            return constructedImageUrl;
        }
        throw new RuntimeException("Some required details are missing");
    }
    private String buildOutputLocation() {
        if (Objects.nonNull(deploymentDetails) && Objects.nonNull(deploymentDetails.website())
                && Objects.nonNull(deploymentDetails.app()) &&
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
        boolean isRemoteBuild = ConfigProvider.getConfig().getValue("mpp.remote.build", Boolean.class);
        String imageRepoUrl = null;
        if(isRemoteBuild){
            imageRepoUrl = completeRemoteImageRepoUrl();
        }else{
            imageRepoUrl = completeImageRepoUrl();
        }
        return new SsrResourceDetails(nameSpace,imageRepoUrl
                , deploymentDetails.app(), deploymentDetails.contextPath(),
                deploymentDetails.healthCheckPath(), deploymentDetails.website(),
                deploymentDetails.environment(), deploymentDetails.port(), deploymentDetails.configMap()
                ,deploymentDetails.secretMap());
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
                buildName,
                this.dockerFilePath
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
        REGULAR, MONO, WITH_BUILD_ARG, MONO_WITH_BUILD_ARG,REMOTE_BUILD
    }

}
