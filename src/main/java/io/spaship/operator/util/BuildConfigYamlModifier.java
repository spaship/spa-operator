package io.spaship.operator.util;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

//TODO refactor this class and remove duplicate codes
public class BuildConfigYamlModifier {

    private static final String TEMPLATE_PATH = "/openshift/build-template.yaml";


    public static InputStream modifyTemplateForMonRepo(InputStream ocTemplate) throws IOException {
        Result result = preProcess(ocTemplate);
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
        Result result = preProcess(ocTemplate);
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

    public static InputStream monoRepoWithBuildArg(InputStream ocTemplate, List<Map<String, String>> buildArgs)
            throws IOException {
        var monoRepoBuildTemplate = modifyTemplateForMonRepo(ocTemplate);
        return modifyTemplateForDockerBuildArg(monoRepoBuildTemplate, buildArgs);
    }

    private static Result preProcess(InputStream ocTemplate) {
        if (Objects.isNull(ocTemplate))
            ocTemplate = BuildConfigYamlModifier.class
                    .getResourceAsStream(TEMPLATE_PATH);
        Yaml yaml = new Yaml();
        return new Result(ocTemplate, yaml);
    }

    private record Result(InputStream ocTemplate, Yaml yaml) {
    }


}
