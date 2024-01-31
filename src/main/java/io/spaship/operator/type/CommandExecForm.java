package io.spaship.operator.type;

import java.util.Map;

public record CommandExecForm(Environment environment, Map<String,String> metadata, CommandExecutionEnums.Command commandType) {
    @Override
    public String toString() {
        return "CommandExecForm{" +
                "environment=" + environment +
                ", metadata=" + metadata +
                ", commandType=" + commandType +
                '}';
    }
}
