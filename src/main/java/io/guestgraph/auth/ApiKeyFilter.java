package io.guestgraph.auth;

import io.guestgraph.domain.Credential;
import io.guestgraph.persistence.TenantStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticates every /api request with a per-tenant API key (header X-API-Key, SHA-256 hash
 * lookup) and binds the tenant to the request. 401 responses are RFC 9457 problem details.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

  public static final String API_KEY_HEADER = "X-API-Key";

  private final TenantStore tenantStore;
  private final ObjectMapper mapper;

  public ApiKeyFilter(TenantStore tenantStore, ObjectMapper mapper) {
    this.tenantStore = tenantStore;
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String apiKey = request.getHeader(API_KEY_HEADER);
    if (apiKey == null || apiKey.isBlank()) {
      unauthorized(response, "Missing " + API_KEY_HEADER + " header");
      return;
    }
    Optional<Credential> credential = tenantStore.findCredentialByApiKeyHash(Sha256.hex(apiKey));
    if (credential.isEmpty()) {
      unauthorized(response, "Unknown or revoked API key");
      return;
    }
    TenantContext.set(credential.get().tenant());
    try {
      ActorResolver.set(
          ActorResolver.resolve(
              credential.get().actorType(),
              credential.get().actorName(),
              request.getHeader(ActorResolver.ACTOR_TYPE_HEADER),
              request.getHeader(ActorResolver.ACTOR_ID_HEADER)));
    } catch (InvalidActorClaimException e) {
      TenantContext.clear();
      invalidActorClaim(response, e.getMessage());
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      ActorResolver.clear();
      TenantContext.clear();
    }
  }

  private void invalidActorClaim(HttpServletResponse response, String detail) throws IOException {
    problem(
        response,
        HttpServletResponse.SC_BAD_REQUEST,
        "invalid-actor-claim",
        "Invalid actor claim",
        detail);
  }

  private void unauthorized(HttpServletResponse response, String detail) throws IOException {
    problem(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "Unauthorized", detail);
  }

  private void problem(
      HttpServletResponse response, int status, String type, String title, String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    Map<String, Object> problem = new LinkedHashMap<>();
    problem.put("type", "https://guestgraph.io/problems/" + type);
    problem.put("title", title);
    problem.put("status", status);
    problem.put("detail", detail);
    response.getWriter().write(mapper.writeValueAsString(problem));
  }
}
