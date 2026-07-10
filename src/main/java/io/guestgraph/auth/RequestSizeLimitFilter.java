package io.guestgraph.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * unbounded liability). A declared Content-Length over the cap is rejected from the header alone; a
 * body without a declared length (Transfer-Encoding: chunked — the bypass a header-only check would
 * miss) is buffered up to cap+1 bytes right here and rejected when it overruns. Both paths answer
 * RFC 9457 413. Memory is bounded by the cap, which the JSON layer would buffer anyway.
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
    long declared = request.getContentLengthLong();
    if (declared > maxRequestBytes) {
      writeProblem(response);
      return;
    }
    if (declared >= 0) {
      // Declared and within the cap; the container enforces the declared length.
      chain.doFilter(request, response);
      return;
    }
    byte[] body = readAtMost(request.getInputStream(), maxRequestBytes);
    if (body == null) {
      writeProblem(response);
      return;
    }
    chain.doFilter(new CachedBodyRequest(request, body), response);
  }

  /** Reads the full stream, or returns null as soon as it exceeds {@code max} bytes. */
  private byte[] readAtMost(InputStream in, long max) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
    byte[] chunk = new byte[8192];
    long total = 0;
    int n;
    while ((n = in.read(chunk)) != -1) {
      total += n;
      if (total > max) {
        return null;
      }
      buffer.write(chunk, 0, n);
    }
    return buffer.toByteArray();
  }

  private void writeProblem(HttpServletResponse response) throws IOException {
    response.setStatus(413);
    response.setContentType("application/problem+json");
    Map<String, Object> problem = new LinkedHashMap<>();
    problem.put("type", "https://guestgraph.io/problems/payload-too-large");
    problem.put("title", "Payload too large");
    problem.put("status", 413);
    problem.put("detail", "Request body exceeds the limit of " + maxRequestBytes + " bytes");
    response.getWriter().write(mapper.writeValueAsString(problem));
  }

  private static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream source = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return source.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
          return source.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
          return source.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
          throw new UnsupportedOperationException("Async body reading is not supported here");
        }
      };
    }
  }
}
