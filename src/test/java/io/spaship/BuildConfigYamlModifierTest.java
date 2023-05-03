package io.spaship;

import io.spaship.operator.util.BuildConfigYamlModifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class BuildConfigYamlModifierTest {

    private BuildConfigYamlModifier modifier;
    private InputStream is;


    @BeforeEach
    void setup() {
        modifier = new BuildConfigYamlModifier();
        is = BuildConfigYamlModifierTest.class
                .getResourceAsStream("/openshift/build-template.yaml");
    }

    @Test
    void testBuildTemplateExists() {
        assertNotNull(is);
    }

    @Test
    void testModifyTemplateForMonRepoWithNullInput() throws IOException {
        InputStream output = modifier.modifyTemplateForMonRepo(null);
        assertNotNull(output);
    }

    @Test
    void testModifyTemplateForMonRepoWithValidInput() throws IOException {
        InputStream output = modifier.modifyTemplateForMonRepo(is);
        assertNotNull(output);
    }

    @Test
    void testModifyTemplateForDockerBuildArgWithNullInput() throws IOException {
        InputStream output = modifier.modifyTemplateForDockerBuildArg(null, new ArrayList<>());
        assertNotNull(output);
    }

    @Test
    void testModifyTemplateForDockerBuildArgWithNullBuildArgs() throws IOException {
        InputStream output = modifier.modifyTemplateForDockerBuildArg(is, null);
        assertNotNull(output);
    }


    @Test
    void testModifyTemplateForDockerBuildArgWithValidInput() throws IOException {
        InputStream input = BuildConfigYamlModifierTest.class
                .getResourceAsStream("/openshift/build-config-mono-template.yaml");
        List<Map<String, String>> buildArgs = new ArrayList<>();
        Map<String, String> buildArg1 = new HashMap<>();
        buildArg1.put("key1", "value1");
        Map<String, String> buildArg2 = new HashMap<>();
        buildArg2.put("key2", "value2");
        buildArgs.add(buildArg1);
        buildArgs.add(buildArg2);
        InputStream output = modifier.modifyTemplateForDockerBuildArg(input, buildArgs);
        assertNotNull(output);
    }

    @Test
    void testMonoRepoWithBuildArg() throws IOException {
        InputStream input = BuildConfigYamlModifierTest.class
                .getResourceAsStream("/openshift/build-config-mono-template.yaml");
        List<Map<String, String>> buildArgs = new ArrayList<>();
        Map<String, String> buildArg1 = new HashMap<>();
        buildArg1.put("key1", "value1");
        Map<String, String> buildArg2 = new HashMap<>();
        buildArg2.put("key2", "value2");
        buildArgs.add(buildArg1);
        buildArgs.add(buildArg2);
        InputStream output = modifier.monoRepoWithBuildArg(input, buildArgs);
        assertNotNull(output);
    }


    @Test
    void testDeploymentTemplateWithConfig() throws IOException {
        Map<String, String> configMap = new HashMap<>();
        configMap.put("one", "First Value");
        configMap.put("two", "Second Value");
        InputStream output = BuildConfigYamlModifier.addDataToConfigMap(null,configMap);
        assertNotNull(output);
    }

    void assertNotNull(InputStream inputStream) {
        org.junit.jupiter.api.Assertions.assertNotNull(inputStream);
        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            String content = result.toString(StandardCharsets.UTF_8);
            System.out.println(content);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }


}
