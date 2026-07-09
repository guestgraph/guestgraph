package io.guestgraph.persistence.mapper;

import io.guestgraph.domain.Guest;
import io.guestgraph.domain.MergeEvent;
import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.domain.SourceRecord;
import io.guestgraph.domain.SourceSystem;
import io.guestgraph.domain.Tenant;
import io.guestgraph.persistence.entity.GuestEntity;
import io.guestgraph.persistence.entity.MergeEventEntity;
import io.guestgraph.persistence.entity.RecordIdentifierEntity;
import io.guestgraph.persistence.entity.SourceRecordEntity;
import io.guestgraph.persistence.entity.SourceSystemEntity;
import io.guestgraph.persistence.entity.TenantEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Entity → domain-record mapping, generated at compile time. unmappedTargetPolicy ERROR fails the
 * build on a forgotten field — no silently dropped data. The write direction is deliberate
 * hand-written entity construction in the stores/adapter.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DomainMappers {

  Tenant toDomain(TenantEntity entity);

  SourceSystem toDomain(SourceSystemEntity entity);

  Guest toDomain(GuestEntity entity);

  MergeEvent toDomain(MergeEventEntity entity);

  @Mapping(target = "sourceSystemId", source = "sourceSystem.id")
  @Mapping(target = "sourceSystemCode", source = "sourceSystem.code")
  @Mapping(target = "payloadJson", source = "payload")
  SourceRecord toDomain(SourceRecordEntity entity);

  List<SourceRecord> toDomainRecords(List<SourceRecordEntity> entities);

  @Mapping(target = "value", source = "valueNormalized")
  NormalizedIdentifier toDomain(RecordIdentifierEntity entity);
}
