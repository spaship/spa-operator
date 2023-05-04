package io.spaship.operator.type;

import java.util.Objects;

public record GitFlowResponse(String buildName, String deploymentName, GitFlowMeta constructedGitFlowMeta) {
    public SsrResourceDetails fetchDeploymentDetails(){
        Objects.requireNonNull(constructedGitFlowMeta,"GitFlowMeta not found");
        return constructedGitFlowMeta.deploymentDetails();
    }
    public String fetchNameSpace(){
        Objects.requireNonNull(constructedGitFlowMeta,"GitFlowMeta not found");
        return constructedGitFlowMeta.nameSpace();
    }
}
