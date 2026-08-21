package io.guestgraph.persistence.repo;

import io.guestgraph.persistence.entity.RecordObjectEntity;
import io.guestgraph.timeline.ObjectObservation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface RecordObjectRepo extends Repository<RecordObjectEntity, UUID> {

  /**
   * Every observation of every object this guest appears on — including observations belonging to
   * other guests, because a newer version naming someone else is exactly what removes this guest
   * from a booking.
   *
   * <p>One statement rather than two so the roster cannot be torn by a concurrent ingest landing
   * between reads. Row volume is bounded by the guest's own object count, not by tenant size.
   */
  @Query(
      """
            select new io.guestgraph.timeline.ObjectObservation(
                ro.sourceRecordId, ro.sourceSystemId, ss.code,
                ro.objectType, ro.objectId, ro.objectRole, ro.objectPosition,
                ro.objectVersion, ro.businessStart, ro.businessEnd,
                l.guestId, sr.extracted,
                coalesce(sr.recordTimestamp, sr.receivedAt), sr.needsReview)
            from RecordObjectEntity ro
                join SourceRecordEntity sr on sr.id = ro.sourceRecordId
                join SourceSystemEntity ss on ss.id = ro.sourceSystemId
                join ResolutionLinkEntity l on l.sourceRecordId = ro.sourceRecordId
            where ro.tenantId = :tenantId
              and exists (
                select 1 from RecordObjectEntity mine
                    join ResolutionLinkEntity ml on ml.sourceRecordId = mine.sourceRecordId
                where mine.tenantId = :tenantId
                  and ml.guestId = :guestId
                  and mine.sourceSystemId = ro.sourceSystemId
                  and mine.objectType = ro.objectType
                  and mine.objectId = ro.objectId)
            order by ro.objectVersion, ro.objectRole, ro.objectPosition, ro.sourceRecordId
            """)
  List<ObjectObservation> observationsForGuestObjects(
      @Param("tenantId") UUID tenantId, @Param("guestId") UUID guestId);

  /** Every observation of one business object, for the object resource's roster and history. */
  @Query(
      """
            select new io.guestgraph.timeline.ObjectObservation(
                ro.sourceRecordId, ro.sourceSystemId, ss.code,
                ro.objectType, ro.objectId, ro.objectRole, ro.objectPosition,
                ro.objectVersion, ro.businessStart, ro.businessEnd,
                l.guestId, sr.extracted,
                coalesce(sr.recordTimestamp, sr.receivedAt), sr.needsReview)
            from RecordObjectEntity ro
                join SourceRecordEntity sr on sr.id = ro.sourceRecordId
                join SourceSystemEntity ss on ss.id = ro.sourceSystemId
                join ResolutionLinkEntity l on l.sourceRecordId = ro.sourceRecordId
            where ro.tenantId = :tenantId and ss.code = :sourceSystemCode
              and ro.objectType = :objectType and ro.objectId = :objectId
            order by ro.objectVersion, ro.objectRole, ro.objectPosition
            """)
  List<ObjectObservation> observationsOfObject(
      @Param("tenantId") UUID tenantId,
      @Param("sourceSystemCode") String sourceSystemCode,
      @Param("objectType") String objectType,
      @Param("objectId") String objectId);
}
