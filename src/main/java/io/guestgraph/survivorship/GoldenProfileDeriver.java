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
    records.stream()
        .sorted(
            Comparator.comparing(SourceRecord::effectiveTimestamp)
                .thenComparing(SourceRecord::receivedAt))
        .forEach(
            record ->
                record
                    .extracted()
                    .forEach(
                        (field, value) -> {
                          if (value != null && !(value instanceof String s && s.isBlank())) {
                            profile.put(field, value);
                          }
                        }));
    return profile;
  }
}
