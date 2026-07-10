package io.guestgraph.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * Serves the complete OpenAPI document at GET /api-docs: the union of the per-feature contracts
 * (specs/&#42;/contracts/openapi.yaml, bundled at build time). The hand-written contracts stay the
 * source of truth — the conformance test proves both that they match the served surface and that
 * this merge equals their union. Outside /api/, so no API key is required (the document contains no
 * tenant data).
 */
@RestController
public class ApiDocsController {

  private final Map<String, Object> mergedDocument;

  public ApiDocsController() {
    this.mergedDocument = merge(loadContracts());
  }

  @GetMapping(value = "/api-docs", produces = "application/json")
  public Map<String, Object> apiDocs() {
    return mergedDocument;
  }

  private static Map<String, Object>[] loadContracts() {
    try {
      Resource[] resources =
          new PathMatchingResourcePatternResolver()
              .getResources("classpath:api-contracts/*/contracts/openapi.yaml");
      Arrays.sort(resources, Comparator.comparing(Resource::getDescription));
      @SuppressWarnings("unchecked")
      Map<String, Object>[] documents = new Map[resources.length];
      Yaml yaml = new Yaml();
      for (int i = 0; i < resources.length; i++) {
        try (InputStream in = resources[i].getInputStream()) {
          documents[i] = yaml.load(in);
        }
      }
      return documents;
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load bundled API contracts", e);
    }
  }

  /**
   * Later features add paths and schemas; on collision (e.g. ProblemDetails redefined per feature)
   * the earliest definition wins — feature contracts must not redefine earlier features' paths.
   */
  private static Map<String, Object> merge(Map<String, Object>[] documents) {
    if (documents.length == 0) {
      throw new IllegalStateException("No bundled API contracts found");
    }
    Map<String, Object> merged = new LinkedHashMap<>(documents[0]);
    Map<String, Object> info = new LinkedHashMap<>(section(merged, "info"));
    info.put("title", "GuestGraph Core API");
    merged.put("info", info);
    Map<String, Object> paths = new LinkedHashMap<>(section(merged, "paths"));
    Map<String, Object> components = new LinkedHashMap<>(section(merged, "components"));
    for (int i = 1; i < documents.length; i++) {
      Map<String, Object> document = documents[i];
      section(document, "paths")
          .forEach(
              (path, item) -> {
                if (paths.putIfAbsent(path, item) != null) {
                  throw new IllegalStateException("Feature contracts redefine path " + path);
                }
              });
      section(document, "components")
          .forEach(
              (kind, entries) -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> target =
                    (Map<String, Object>)
                        components.computeIfAbsent(kind, k -> new LinkedHashMap<String, Object>());
                if (entries instanceof Map<?, ?> entryMap) {
                  entryMap.forEach((name, schema) -> target.putIfAbsent((String) name, schema));
                }
              });
      Object version = section(document, "info").get("version");
      if (version != null) {
        info.put("version", version);
      }
    }
    merged.put("paths", paths);
    merged.put("components", components);
    return merged;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> section(Map<String, Object> document, String key) {
    Object value = document.get(key);
    return value instanceof Map ? (Map<String, Object>) value : Map.of();
  }
}
