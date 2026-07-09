package io.guestgraph.auth;

import io.guestgraph.domain.Tenant;
import io.guestgraph.persistence.TenantDao;
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
 * Authenticates every /api request with a per-tenant API key (header X-API-Key,
 * SHA-256 hash lookup) and binds the tenant to the request. 401 responses are
 * RFC 9457 problem details.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final TenantDao tenantDao;
    private final ObjectMapper mapper;

    public ApiKeyFilter(TenantDao tenantDao, ObjectMapper mapper) {
        this.tenantDao = tenantDao;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            unauthorized(response, "Missing " + API_KEY_HEADER + " header");
            return;
        }
        Optional<Tenant> tenant = tenantDao.findByApiKeyHash(Sha256.hex(apiKey));
        if (tenant.isEmpty()) {
            unauthorized(response, "Unknown or revoked API key");
            return;
        }
        TenantContext.set(tenant.get());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://guestgraph.io/problems/unauthorized");
        problem.put("title", "Unauthorized");
        problem.put("status", 401);
        problem.put("detail", detail);
        response.getWriter().write(mapper.writeValueAsString(problem));
    }
}
