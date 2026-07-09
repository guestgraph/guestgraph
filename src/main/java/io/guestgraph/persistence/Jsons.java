package io.guestgraph.persistence;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Central JSON (de)serialization for jsonb columns. */
@Component
public class Jsons {

    private final ObjectMapper mapper;

    public Jsons(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    public List<String> stringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, new TypeReference<List<String>>() {
        });
    }

    public List<Map<String, Object>> mapList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    public List<UUID> uuidList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, new TypeReference<List<UUID>>() {
        });
    }
}
