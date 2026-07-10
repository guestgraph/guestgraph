package io.guestgraph.survivorship;

import io.guestgraph.domain.SourceRecord;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure survivorship function: source records in, golden profile out. Rule v1: per field, most
 * recent non-null wins; "most recent" is the record's source timestamp, falling back to time of
 * receipt (research R7).
 */
public class GoldenProfileDeriver {

  public Map<String, Object> derive(List<SourceRecord> records) {
    Map<String, Object> profile = new LinkedHashMap<>();
    String[] realEmail = new String[1];
    String[] maskedEmail = new String[1];
    records.stream()
        .sorted(
            Comparator.comparing(SourceRecord::effectiveTimestamp)
                .thenComparing(SourceRecord::receivedAt))
        .forEach(
            record -> {
              boolean masked = Boolean.TRUE.equals(record.extracted().get("emailMasked"));
              record
                  .extracted()
                  .forEach(
                      (field, value) -> {
                        if (value == null || (value instanceof String s && s.isBlank())) {
                          return;
                        }
                        // Masked-alias guard (US3): relay addresses never overwrite a
                        // real email; they fill only when no real address exists.
                        if (field.equals("email")) {
                          if (masked) {
                            maskedEmail[0] = (String) value;
                          } else {
                            realEmail[0] = (String) value;
                          }
                          return;
                        }
                        if (field.equals("emailMasked")) {
                          return;
                        }
                        profile.put(field, value);
                      });
            });
    if (realEmail[0] != null) {
      profile.put("email", realEmail[0]);
    } else if (maskedEmail[0] != null) {
      profile.put("email", maskedEmail[0]);
      profile.put("emailMasked", true);
    }
    return profile;
  }
}
