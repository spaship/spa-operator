package io.spaship.operator.type;

import java.util.List;

public record UpdateConfigOrSecretRequest(SsrResourceDetails ssrResourceDetails, List<String> keysToDelete) {
}
