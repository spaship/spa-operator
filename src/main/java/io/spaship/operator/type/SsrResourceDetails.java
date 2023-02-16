package io.spaship.operator.type;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public record SsrResourceDetails(String nameSpace, String imageUrl, String app, String contextPath, String healthCheckPath, String website,
                              String environment,
                              Map<String, String> configMap) {


    public Map<String, String> toTemplateParameterMap(){
        Map<String, String> params = new HashMap<>();

        if(Objects.nonNull(imageUrl))
            params.put("IMAGE-URL", imageUrl);
        if(Objects.nonNull(app))
            params.put("APP", app);
        if(Objects.nonNull(contextPath))
            params.put("CONTEXT-PATH", contextPath);
        if(Objects.nonNull(healthCheckPath))
            params.put("HEALTH-CHECK-PATH", healthCheckPath);
        if(Objects.nonNull(website))
            params.put("WEBSITE", website);
        if(Objects.nonNull(environment))
            params.put("ENV", environment);

        return params;
    }




}