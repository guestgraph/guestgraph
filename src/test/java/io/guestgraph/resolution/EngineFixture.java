package io.guestgraph.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.domain.SourceRecord;
import io.guestgraph.survivorship.GoldenProfileDeriver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Table-driven test harness: named records in → expected clusters out. */
public class EngineFixture {

  public static final UUID TENANT = UUID.nameUUIDFromBytes("tenant:test".getBytes());

  public final InMemoryGraph graph = new InMemoryGraph();
  public final ResolutionEngine engine =
      new ResolutionEngine(graph, new DeterministicMatcher(), new GoldenProfileDeriver());

  private final Map<String, UUID> recordIdsByName = new LinkedHashMap<>();
  private final Map<String, ResolutionOutcome> outcomesByName = new LinkedHashMap<>();
  private int sequence = 0;

  public RecordBuilder record(String name) {
    return new RecordBuilder(name);
  }

  public ResolutionOutcome outcomeOf(String name) {
    return outcomesByName.get(name);
  }

  public UUID recordId(String name) {
    return recordIdsByName.get(name);
  }

  /** Asserts the current record→guest clustering, expressed with record names. */
  public void assertClusters(Set<Set<String>> expected) {
    Set<Set<String>> actual =
        graph.clusters().values().stream()
            .map(cluster -> cluster.stream().map(this::nameOf).collect(Collectors.toSet()))
            .collect(Collectors.toSet());
    assertThat(actual).isEqualTo(expected);
  }

  private String nameOf(UUID recordId) {
    return recordIdsByName.entrySet().stream()
        .filter(e -> e.getValue().equals(recordId))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(recordId.toString());
  }

  public class RecordBuilder {
    private final String name;
    private final List<NormalizedIdentifier> identifiers = new ArrayList<>();
    private final Map<String, Object> extracted = new HashMap<>();
    private Instant recordTimestamp;
    private boolean needsReview;

    private RecordBuilder(String name) {
      this.name = name;
    }

    public RecordBuilder email(String normalized) {
      identifiers.add(new NormalizedIdentifier(IdentifierType.EMAIL, normalized));
      return this;
    }

    public RecordBuilder phone(String normalized) {
      identifiers.add(new NormalizedIdentifier(IdentifierType.PHONE, normalized));
      return this;
    }

    public RecordBuilder loyaltyId(String normalized) {
      identifiers.add(new NormalizedIdentifier(IdentifierType.LOYALTY_ID, normalized));
      return this;
    }

    public RecordBuilder field(String key, Object value) {
      extracted.put(key, value);
      return this;
    }

    public RecordBuilder at(Instant timestamp) {
      this.recordTimestamp = timestamp;
      return this;
    }

    public RecordBuilder needsReview() {
      this.needsReview = true;
      return this;
    }

    public ResolutionOutcome resolve() {
      return resolve(Set.of());
    }

    public ResolutionOutcome resolve(Set<UUID> excludedGuestIds) {
      SourceRecord record = build();
      graph.register(record);
      ResolutionOutcome outcome = engine.resolve(record, new HashSet<>(excludedGuestIds));
      outcomesByName.put(name, outcome);
      return outcome;
    }

    public SourceRecord build() {
      UUID id = recordIdsByName.computeIfAbsent(name, n -> UUID.randomUUID());
      return new SourceRecord(
          id,
          TENANT,
          UUID.nameUUIDFromBytes("source:test".getBytes()),
          "test-source",
          name,
          "{}",
          extracted,
          identifiers,
          recordTimestamp,
          needsReview,
          List.of(),
          Instant.parse("2026-07-09T10:00:00Z").plusSeconds(sequence++));
    }
  }
}
