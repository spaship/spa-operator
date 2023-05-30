package io.spaship.operator.util;


import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.yaml.snakeyaml.Yaml;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

//TODO refactor this class and remove duplicate codes
public class BuildConfigYamlModifier {
    private static final Logger LOG = LoggerFactory.getLogger(BuildConfigYamlModifier.class);

    private static final String BUILD_TEMPLATE_PATH = "/openshift/build-template.yaml";
    private static final String DEPLOYMENT_TEMPLATE_PATH = "/openshift/ssr-deployment-template.yaml";
    private static final String EGRESS_TEMPLATE_PATH = "/openshift/tenant-egress-template.yaml";


    public static InputStream modifyTemplateForMonRepo(InputStream ocTemplate) throws IOException {
        Result result = preProcess(ocTemplate,BUILD_TEMPLATE_PATH);
        Map<String, Object> template = result.yaml().load(result.ocTemplate());
        Map<String, Object> buildConfig = (Map<String, Object>) ((ArrayList) template.get("objects")).get(0);
        Map<String, Object> spec = (Map<String, Object>) buildConfig.get("spec");
        Map<String, Object> source = (Map<String, Object>) spec.get("source");
        source.put("contextDir", "${CONTEXT_DIR}");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        result.yaml().dump(template, new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        result.ocTemplate().close();
        return new ByteArrayInputStream(outputStream.toByteArray());
    }


    public static InputStream modifyTemplateForDockerBuildArg(InputStream ocTemplate, List<Map<String, String>> buildArgs) throws IOException {
        Result result = preProcess(ocTemplate,BUILD_TEMPLATE_PATH);
        Map<String, Object> template = result.yaml().load(result.ocTemplate());
        Map<String, Object> buildConfig = (Map<String, Object>) ((ArrayList) template.get("objects")).get(0);
        Map<String, Object> spec = (Map<String, Object>) buildConfig.get("spec");
        Map<String, Object> strategy = (Map<String, Object>) spec.get("strategy");
        Map<String, Object> dockerStrategy = (Map<String, Object>) strategy.get("dockerStrategy");
        dockerStrategy.put("buildArgs", buildArgs);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        result.yaml().dump(template, new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        result.ocTemplate().close();
        return new ByteArrayInputStream(outputStream.toByteArray());
    }

    @SneakyThrows
    public static InputStream addDataToConfigMap(InputStream ocTemplate, Map<String, String> additionalData){
        Result result = preProcess(ocTemplate,DEPLOYMENT_TEMPLATE_PATH);
        Map<String, Object> parSedTemplate = result.yaml().load(result.ocTemplate());
        List<Map<String, Object>> objects = (List<Map<String, Object>>) parSedTemplate.get("objects");

        Map<String, Object> configMap = null;
        for (Map<String, Object> object : objects) {
            if (object.get("kind").equals("ConfigMap")) {
                configMap = object;
                break;
            }
        }

        Objects.requireNonNull(configMap,"Object ConfigMap in containerized deployment template is null");
        if (configMap != null) {
            Map<String, String> data = (Map<String, String>) configMap.get("data");
            data.putAll(additionalData);
        }
        // Serialize the modified template to a ByteArrayOutputStream
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        result.yaml().dump(parSedTemplate, new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        result.ocTemplate().close();
        return new ByteArrayInputStream(outputStream.toByteArray());
    }


    public static List<Map<String, Object>> extractEgressFromTemplate(){
        Result result = preProcess(null,EGRESS_TEMPLATE_PATH);
        Map<String, Object> data = result.yaml().load(result.ocTemplate());
        List<Map<String, Object>> objects = (List<Map<String, Object>>) data.get("objects");
        List<Map<String, Object>> parsedEgressRules = null;
        for (Map<String, Object> object : objects) {
            if ("TenantEgress".equals(object.get("kind"))) {
                Map<String, Object> spec = (Map<String, Object>) object.get("spec");
                parsedEgressRules = (List<Map<String, Object>>) spec.get("egress");
                break;
            }
        }
        return parsedEgressRules;
    }

    public static InputStream monoRepoWithBuildArg(InputStream ocTemplate, List<Map<String, String>> buildArgs)
            throws IOException {
        var monoRepoBuildTemplate = modifyTemplateForMonRepo(ocTemplate);
        return modifyTemplateForDockerBuildArg(monoRepoBuildTemplate, buildArgs);
    }

    private static Result preProcess(InputStream ocTemplate,String templatePath) {
        if (Objects.isNull(ocTemplate))
            ocTemplate = BuildConfigYamlModifier.class
                    .getResourceAsStream(templatePath);
        Yaml yaml = new Yaml();
        return new Result(ocTemplate, yaml);
    }

    public static InputStream modifyForRemoteBuild(InputStream is) {
        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(is);

        List<Map<String, Object>> objects = (List<Map<String, Object>>) data.get("objects");
        Map<String, Object> buildConfig = objects.get(0);
        Map<String, Object> spec = (Map<String, Object>) buildConfig.get("spec");
        Map<String, Object> output = (Map<String, Object>) spec.get("output");
        Map<String, Object> to = (Map<String, Object>) output.get("to");

        to.put("kind", "DockerImage");
        to.put("name", "${REPOSITORY_URL}:${IMAGE_TAG}");

        Map<String, String> pushSecret = new HashMap<>();
        pushSecret.put("name", "${IMAGE_PUSH_SECRET}");
        output.put("pushSecret", pushSecret);

        String newYaml = yaml.dump(data);

        return new ByteArrayInputStream(newYaml.getBytes(StandardCharsets.UTF_8));
    }

    private record Result(InputStream ocTemplate, Yaml yaml) {
    }


}
