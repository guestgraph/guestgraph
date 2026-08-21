package io.guestgraph.ingest;

import io.guestgraph.api.IngestDtos.IngestRecordRequest;
import io.guestgraph.api.IngestDtos.IngestResult;
import io.guestgraph.api.IngestDtos.SourceObjectDto;
import io.guestgraph.domain.IdentifierQualityRule;
import io.guestgraph.domain.IngestStatus;
import io.guestgraph.domain.ObjectRole;
import io.guestgraph.domain.RecordObject;
import io.guestgraph.domain.RuleEffect;
import io.guestgraph.domain.RuleMatchKind;
import io.guestgraph.domain.SourceRecord;
import io.guestgraph.domain.SourceSystem;
import io.guestgraph.persistence.Jsons;
import io.guestgraph.persistence.SourceRecordStore;
import io.guestgraph.persistence.SourceSystemStore;
import io.guestgraph.persistence.repo.ResolutionLinkRepo;
import io.guestgraph.resolution.GraphPort;
import io.guestgraph.resolution.ResolutionEngine;
import io.guestgraph.resolution.ResolutionOutcome;
import io.guestgraph.resolution.TenantLock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingest pipeline: store the original immutably, extract + normalize, resolve synchronously
 * (FR-004..FR-008). Each record runs in its own transaction under the per-tenant lock, so one bad
 * record never sinks a batch.
 */
@Service
public class IngestService {

  private final SourceSystemStore sourceSystemStore;
  private final SourceRecordStore sourceRecordStore;
  private final ResolutionLinkRepo linkRepo;
  private final RecordExtractor extractor;
  private final ResolutionEngine engine;
  private final GraphPort graph;
  private final TenantLock tenantLock;
  private final Jsons jsons;

  public IngestService(
      SourceSystemStore sourceSystemStore,
      SourceRecordStore sourceRecordStore,
      ResolutionLinkRepo linkRepo,
      RecordExtractor extractor,
      ResolutionEngine engine,
      GraphPort graph,
      TenantLock tenantLock,
      Jsons jsons) {
    this.sourceSystemStore = sourceSystemStore;
    this.sourceRecordStore = sourceRecordStore;
    this.linkRepo = linkRepo;
    this.extractor = extractor;
    this.engine = engine;
    this.graph = graph;
    this.tenantLock = tenantLock;
    this.jsons = jsons;
  }

  @Transactional
  public IngestResult ingest(UUID tenantId, IngestRecordRequest request) {
    SourceSystem source =
        sourceSystemStore
            .findByCode(tenantId, request.sourceSystem())
            .orElseThrow(() -> new UnknownSourceSystemException(request.sourceSystem()));

    tenantLock.acquire(tenantId);

    Optional<UUID> existing =
        sourceRecordStore.findIdByExternalKey(tenantId, source.id(), request.externalKey());
    if (existing.isPresent()) {
      UUID guestId = linkRepo.guestIdByRecord(tenantId, existing.get()).orElse(null);
      return new IngestResult(
          request.externalKey(),
          existing.get(),
          guestId,
          IngestStatus.DUPLICATE_IGNORED,
          // The stored record's flag, not a constant — neither field masks the other.
          sourceRecordStore.needsReview(tenantId, existing.get()),
          List.of(),
          null);
    }

    List<String> maskedDomains =
        graph.qualityRules(tenantId).stream()
            .filter(r -> r.rule() == RuleEffect.MASKED_ALIAS)
            .filter(r -> r.matchKind() == RuleMatchKind.EMAIL_DOMAIN)
            .map(IdentifierQualityRule::valueNormalized)
            .toList();
    RecordExtractor.Extraction extraction =
        extractor.extract(source.code(), request.payload(), maskedDomains);
    List<String> objectReasons = objectReasons(request);
    SourceRecord record =
        new SourceRecord(
            UUID.randomUUID(),
            tenantId,
            source.id(),
            source.code(),
            request.externalKey(),
            jsons.write(request.payload()),
            extraction.extracted(),
            extraction.identifiers(),
            request.recordTimestamp(),
            extraction.needsReview() || !objectReasons.isEmpty(),
            concat(extraction.reasons(), objectReasons),
            Instant.now());
    sourceRecordStore.insert(record);
    sourceRecordStore.insertBlockKeys(tenantId, record.id(), extraction.blockKeys());
    objectIdentity(tenantId, source, record, request)
        .ifPresent(sourceRecordStore::insertRecordObject);

    ResolutionOutcome outcome = engine.resolve(record);
    // The status is the real resolution outcome; review flags travel beside it, not over it.
    return new IngestResult(
        request.externalKey(),
        record.id(),
        outcome.guestId(),
        outcome.status(),
        record.needsReview(),
        outcome.pendingReviewIds(),
        null);
  }

  /**
   * The record's business-object companion, when the submission carried a usable one. A submission
   * whose object identity is incomplete or whose version is not an instant produces no companion:
   * the record is still stored and flagged, but it joins no roster rather than joining one under a
   * guessed version (FR-024, Constitution III).
   */
  private Optional<RecordObject> objectIdentity(
      UUID tenantId, SourceSystem source, SourceRecord record, IngestRecordRequest request) {
    SourceObjectDto dto = request.sourceObject();
    if (dto == null || !identityFaults(dto).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        RecordObject.of(
            tenantId,
            record.id(),
            source.id(),
            dto.type().trim(),
            dto.id().trim(),
            ObjectRole.valueOf(dto.role().trim().toUpperCase(Locale.ROOT)),
            dto.position(),
            dto.version(),
            dto.businessStart(),
            dto.businessEnd()));
  }

  /**
   * Everything worth flagging about the submitted object identity — never a reason to reject
   * (Constitution III).
   */
  private static List<String> objectReasons(IngestRecordRequest request) {
    SourceObjectDto dto = request.sourceObject();
    if (dto == null) {
      return List.of();
    }
    List<String> reasons = new ArrayList<>(identityFaults(dto));
    // "Flagged, never guessed" applies to the business dates too, even though they are
    // optional and the raw values survive in the stored payload.
    if (dto.businessStartUnparseable()) {
      reasons.add("sourceObject.businessStart: not a parseable instant");
    }
    if (dto.businessEndUnparseable()) {
      reasons.add("sourceObject.businessEnd: not a parseable instant");
    }
    if (dto.version() != null
        && request.recordTimestamp() != null
        && !request.recordTimestamp().equals(dto.version())) {
      // Survivorship and roster supersession must order observations identically (FR-020).
      // Flag-only: the version itself is usable, so the observation still joins its roster —
      // suppressing it would erase an otherwise valid booking from every timeline.
      reasons.add("recordTimestamp: does not equal sourceObject.version");
    }
    return reasons;
  }

  /**
   * The subset that makes the object identity unusable, so no companion row can be written and the
   * record joins no roster (FR-024). A record with any of these is still stored and flagged.
   */
  private static List<String> identityFaults(SourceObjectDto dto) {
    List<String> faults = new ArrayList<>();
    if (isBlank(dto.type()) || isBlank(dto.id())) {
      faults.add("sourceObject: type and id are required");
    }
    if (isBlank(dto.role())) {
      faults.add("sourceObject.role: required");
    } else if (!isKnownRole(dto.role())) {
      faults.add("sourceObject.role: not one of PRIMARY_GUEST, ADDITIONAL_GUEST, BOOKER");
    }
    if (dto.version() == null) {
      // Absent, or present but unparseable — either way there is no instant to order rosters by.
      faults.add("sourceObject.version: missing or not a parseable instant");
    }
    return faults;
  }

  private static boolean isKnownRole(String role) {
    String candidate = role.trim().toUpperCase(Locale.ROOT);
    for (ObjectRole known : ObjectRole.values()) {
      if (known.name().equals(candidate)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static List<String> concat(List<String> first, List<String> second) {
    if (second.isEmpty()) {
      return first;
    }
    List<String> all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }
}
