package io.guestgraph.ingest;

import io.guestgraph.api.IngestDtos.IngestRecordRequest;
import io.guestgraph.api.IngestDtos.IngestResult;
import io.guestgraph.domain.IngestStatus;
import io.guestgraph.domain.SourceRecord;
import io.guestgraph.domain.SourceSystem;
import io.guestgraph.persistence.Jsons;
import io.guestgraph.persistence.ResolutionLinkDao;
import io.guestgraph.persistence.SourceRecordDao;
import io.guestgraph.persistence.SourceSystemDao;
import io.guestgraph.resolution.ResolutionEngine;
import io.guestgraph.resolution.ResolutionOutcome;
import io.guestgraph.resolution.TenantLock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingest pipeline: store the original immutably, extract + normalize, resolve
 * synchronously (FR-004..FR-008). Each record runs in its own transaction under
 * the per-tenant lock, so one bad record never sinks a batch.
 */
@Service
public class IngestService {

    private final SourceSystemDao sourceSystemDao;
    private final SourceRecordDao sourceRecordDao;
    private final ResolutionLinkDao linkDao;
    private final RecordExtractor extractor;
    private final ResolutionEngine engine;
    private final TenantLock tenantLock;
    private final Jsons jsons;

    public IngestService(SourceSystemDao sourceSystemDao, SourceRecordDao sourceRecordDao,
            ResolutionLinkDao linkDao, RecordExtractor extractor, ResolutionEngine engine,
            TenantLock tenantLock, Jsons jsons) {
        this.sourceSystemDao = sourceSystemDao;
        this.sourceRecordDao = sourceRecordDao;
        this.linkDao = linkDao;
        this.extractor = extractor;
        this.engine = engine;
        this.tenantLock = tenantLock;
        this.jsons = jsons;
    }

    @Transactional
    public IngestResult ingest(UUID tenantId, IngestRecordRequest request) {
        SourceSystem source = sourceSystemDao.findByCode(tenantId, request.sourceSystem())
                .orElseThrow(() -> new UnknownSourceSystemException(request.sourceSystem()));

        tenantLock.acquire(tenantId);

        Optional<UUID> existing = sourceRecordDao.findIdByExternalKey(tenantId, source.id(), request.externalKey());
        if (existing.isPresent()) {
            UUID guestId = linkDao.guestIdByRecord(tenantId, existing.get()).orElse(null);
            return new IngestResult(request.externalKey(), existing.get(), guestId,
                    IngestStatus.DUPLICATE_IGNORED, java.util.List.of(), null);
        }

        RecordExtractor.Extraction extraction = extractor.extract(source.code(), request.payload());
        SourceRecord record = new SourceRecord(UUID.randomUUID(), tenantId, source.id(), source.code(),
                request.externalKey(), jsons.write(request.payload()), extraction.extracted(),
                extraction.identifiers(), request.recordTimestamp(), extraction.needsReview(),
                extraction.reasons(), Instant.now());
        sourceRecordDao.insert(record);

        ResolutionOutcome outcome = engine.resolve(record);
        IngestStatus status = record.needsReview() || !outcome.pendingReviewIds().isEmpty()
                ? IngestStatus.NEEDS_REVIEW
                : outcome.status();
        return new IngestResult(request.externalKey(), record.id(), outcome.guestId(), status,
                outcome.pendingReviewIds(), null);
    }
}
