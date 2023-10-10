package io.spaship.operator.type;

import java.util.Map;

public record CommandExecForm(Environment environment, Map<String,String> metadata) {
    @Override
    public String toString() {
        return "CommandExecForm{" +
                "environment=" + environment +
                ", metadata=" + metadata +
                '}';
    }
}
