package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The request-body cap answers RFC 9457 413 on both transports: a declared Content-Length over the
 * cap (rejected before reading) and a chunked body with no declared length (aborted while
 * streaming) — the latter is the bypass a header-only check would miss.
 */
class RequestSizeLimitTest extends PostgresIntegrationTest {

  private static final long CAP = 5 * 1024 * 1024; // guestgraph.max-request-bytes default

  @Test
  void declaredContentLengthOverCapIs413ProblemDetails() throws Exception {
    try (Socket socket = new Socket("localhost", port)) {
      PrintWriter out = new PrintWriter(socket.getOutputStream(), false, StandardCharsets.US_ASCII);
      out.print("POST /api/v1/records HTTP/1.1\r\n");
      out.print("Host: localhost\r\n");
      out.print("X-API-Key: " + TENANT_A_KEY + "\r\n");
      out.print("Content-Type: application/json\r\n");
      out.print("Content-Length: " + (CAP + 1) + "\r\n");
      out.print("\r\n");
      out.flush(); // headers only — the server must reject without the body

      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      String statusLine = in.readLine();
      assertThat(statusLine).contains("413");
      StringBuilder rest = new StringBuilder();
      String line;
      while ((line = in.readLine()) != null) {
        rest.append(line).append('\n');
      }
      assertThat(rest.toString())
          .contains("application/problem+json")
          .contains("payload-too-large");
    }
  }

  @Test
  void chunkedBodyOverCapIs413ProblemDetails() throws Exception {
    // Slightly over the cap so the abort triggers late and Tomcat can swallow the rest.
    byte[] body = new byte[(int) CAP + 64 * 1024];
    Arrays.fill(body, (byte) 'a');
    body[0] = '{'; // keep Jackson parsing (and therefore reading) the stream

    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/records"))
            .header("X-API-Key", TENANT_A_KEY)
            .header("Content-Type", "application/json")
            // ofInputStream sends no Content-Length → Transfer-Encoding: chunked
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    assertThat(response.body()).contains("payload-too-large");
  }
}
