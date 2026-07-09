package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class ProblemDetailsTest extends PostgresIntegrationTest {

    @Test
    void unknownPathYieldsRfc9457ProblemDetails() {
        ResponseEntity<String> response = api(TENANT_A_KEY).get().uri("/api/v1/no-such-endpoint")
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        JsonNode problem = json(response.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(404);
        assertThat(problem.has("title")).isTrue();
    }
}
