package io.spaship.operator.type;

public record GitFlowResponse(String buildName, String deploymentName, GitFlowMeta constructedGitFlowMeta) {
}
