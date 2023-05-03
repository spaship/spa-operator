package io.spaship.operator.type;

public record K8sResourceLog(String resourceName, Source source, String log) {

    public enum Source {
        POD, BUILD
    }
}
