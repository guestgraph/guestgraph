package io.guestgraph.ingest;

import io.guestgraph.api.IngestDtos.IngestRecordRequest;
import io.guestgraph.api.IngestDtos.IngestResult;
import io.guestgraph.domain.IdentifierQualityRule;
import io.guestgraph.domain.IngestStatus;
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
import java.util.List;
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
            extraction.needsReview(),
            extraction.reasons(),
            Instant.now());
    sourceRecordStore.insert(record);
    sourceRecordStore.insertBlockKeys(tenantId, record.id(), extraction.blockKeys());

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
}
