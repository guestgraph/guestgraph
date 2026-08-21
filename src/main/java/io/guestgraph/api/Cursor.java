package io.guestgraph.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * The keyset cursor every paged endpoint in this API uses: an opaque token carrying the last-seen
 * ordering key, never an offset.
 *
 * <p>Opaque on purpose. A raw offset is a contract commitment — it fixes how a page is located, so
 * moving a read from in-memory derivation into SQL, or changing an ordering, becomes a breaking
 * change. A cursor keeps that replaceable. It also seeks rather than skipping, so deep pages do not
 * pay the scan-and-discard that {@code OFFSET} does.
 *
 * <p>Keyset paging is not a snapshot: rows added between requests may still appear or shift.
 */
public final class Cursor {

  private Cursor() {}

  public record Key(Instant time, String id) {

    /**
     * The id half as a UUID, for endpoints whose ordering tiebreak is a row id.
     *
     * @throws BadRequestException when it is not one — cursors are opaque and every paged endpoint
     *     advertises the same format, so a cursor from one pasted into another is an ordinary
     *     client mistake and must read as 400, not 500
     */
    public UUID uuid() {
      try {
        return UUID.fromString(id);
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("cursor is not a paging cursor for this collection");
      }
    }
  }

  public static String encode(Instant time, String id) {
    // ISO-8601, not epoch millis: Postgres timestamptz keeps microseconds, and a truncated
    // cursor time makes the keyset seek re-include the boundary row — duplicates on the next
    // page. A space separates because an ISO instant can never contain one, whatever the id holds.
    String raw = time + " " + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * @throws BadRequestException when the token is not one this service issued — a malformed cursor
   *     is a client error, not a silent reset to page one
   */
  public static Key decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int split = raw.indexOf(' ');
      if (split < 0) {
        throw new IllegalArgumentException("missing separator");
      }
      return new Key(Instant.parse(raw.substring(0, split)), raw.substring(split + 1));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new BadRequestException("cursor is not a valid paging cursor");
    }
  }
}
