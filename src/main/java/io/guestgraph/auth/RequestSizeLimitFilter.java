package io.guestgraph.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Caps request body size on /api (payloads are stored immutably forever — an unbounded body is an
 * unbounded liability). Over-limit requests get an RFC 9457 413. Declared Content-Length is checked
 * up front; chunked requests are capped by Tomcat's max-swallow/part limits at the connector level.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

  private final long maxRequestBytes;
  private final ObjectMapper mapper;

  public RequestSizeLimitFilter(
      @Value("${guestgraph.max-request-bytes:5242880}") long maxRequestBytes, ObjectMapper mapper) {
    this.maxRequestBytes = maxRequestBytes;
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
    long contentLength = request.getContentLengthLong();
    if (contentLength > maxRequestBytes) {
      response.setStatus(413);
      response.setContentType("application/problem+json");
      Map<String, Object> problem = new LinkedHashMap<>();
      problem.put("type", "https://guestgraph.io/problems/payload-too-large");
      problem.put("title", "Payload too large");
      problem.put("status", 413);
      problem.put("detail", "Request body exceeds the limit of " + maxRequestBytes + " bytes");
      response.getWriter().write(mapper.writeValueAsString(problem));
      return;
    }
    chain.doFilter(request, response);
  }
}
