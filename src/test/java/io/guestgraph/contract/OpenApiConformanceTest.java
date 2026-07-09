package io.guestgraph.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.integration.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Two-way drift gate between the served API and specs/001-core-identity-resolution/
 * contracts/openapi.yaml: every documented operation must exist, and every served /api operation
 * must be documented. (Response-schema validation is exercised field-by-field in the integration
 * suites; this test pins the surface itself.)
 */
class OpenApiConformanceTest extends PostgresIntegrationTest {

  private static final Path CONTRACT =
      Path.of("specs/001-core-identity-resolution/contracts/openapi.yaml");

  @Autowired RequestMappingHandlerMapping handlerMapping;

  @Test
  void servedApiMatchesTheContractBothWays() throws Exception {
    assertThat(CONTRACT).exists();
    Set<String> documented = documentedOperations();
    Set<String> served = servedOperations();

    assertThat(served).as("every documented operation is served").containsAll(documented);
    assertThat(documented)
        .as("every served /api operation is documented in openapi.yaml")
        .containsAll(served);
  }

  private Set<String> documentedOperations() throws Exception {
    Map<String, Object> spec = new Yaml().load(Files.readString(CONTRACT));
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
    Set<String> operations = new LinkedHashSet<>();
    paths.forEach(
        (path, item) ->
            item.keySet().stream()
                .filter(k -> Set.of("get", "post", "put", "patch", "delete").contains(k))
                .forEach(method -> operations.add(normalize(method, "/api/v1" + path))));
    return operations;
  }

  private Set<String> servedOperations() {
    Set<String> operations = new LinkedHashSet<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
        handlerMapping.getHandlerMethods().entrySet()) {
      RequestMappingInfo info = entry.getKey();
      if (info.getPathPatternsCondition() == null) {
        continue;
      }
      info.getPathPatternsCondition()
          .getPatterns()
          .forEach(
              pattern -> {
                String path = pattern.getPatternString();
                if (path.startsWith("/api/")) {
                  info.getMethodsCondition()
                      .getMethods()
                      .forEach(method -> operations.add(normalize(method.name(), path)));
                }
              });
    }
    return operations;
  }

  /** Placeholder names don't matter for conformance: {guestId} ≡ {id}. */
  private String normalize(String method, String path) {
    return method.toLowerCase(Locale.ROOT) + " " + path.replaceAll("\\{[^}]+}", "{}");
  }
}
