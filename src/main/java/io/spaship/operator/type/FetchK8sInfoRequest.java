package io.spaship.operator.type;

public record FetchK8sInfoRequest(String objectName, String ns, int upto, boolean isProduction) {

}
