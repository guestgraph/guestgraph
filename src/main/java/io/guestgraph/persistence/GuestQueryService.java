package io.guestgraph.persistence;

import io.guestgraph.domain.Guest;
import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.domain.SourceRecord;
import io.guestgraph.normalize.EmailNormalizer;
import io.guestgraph.normalize.IdDocumentHasher;
import io.guestgraph.normalize.PhoneNormalizer;
import io.guestgraph.persistence.mapper.DomainMappers;
import io.guestgraph.persistence.repo.GuestRepo;
import io.guestgraph.persistence.repo.IdentifierRepo;
import io.guestgraph.persistence.repo.ResolutionLinkRepo;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the graph (US2): golden profiles, source records, identifier lookup. */
@Service
@Transactional(readOnly = true)
public class GuestQueryService {

  /** A guest with its identifiers and how many source records support it. */
  public record GuestView(Guest guest, List<NormalizedIdentifier> identifiers, int recordCount) {}

  private final GuestRepo guestRepo;
  private final IdentifierRepo identifierRepo;
  private final ResolutionLinkRepo linkRepo;
  private final SourceRecordStore sourceRecordStore;
  private final DomainMappers mappers;
  private final String defaultPhoneRegion;

  public GuestQueryService(
      GuestRepo guestRepo,
      IdentifierRepo identifierRepo,
      ResolutionLinkRepo linkRepo,
      SourceRecordStore sourceRecordStore,
      DomainMappers mappers,
      @Value("${guestgraph.default-phone-region:}") String defaultPhoneRegion) {
    this.guestRepo = guestRepo;
    this.identifierRepo = identifierRepo;
    this.linkRepo = linkRepo;
    this.sourceRecordStore = sourceRecordStore;
    this.mappers = mappers;
    this.defaultPhoneRegion =
        defaultPhoneRegion == null || defaultPhoneRegion.isBlank() ? null : defaultPhoneRegion;
  }

  public Optional<GuestView> findGuest(UUID tenantId, UUID guestId) {
    return guestRepo
        .findGuest(tenantId, guestId)
        .map(entity -> view(tenantId, mappers.toDomain(entity)));
  }

  /** The guest's source records exactly as received; empty only if the guest exists recordless. */
  public Optional<List<SourceRecord>> findRecords(UUID tenantId, UUID guestId) {
    if (guestRepo.findGuest(tenantId, guestId).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(sourceRecordStore.findByGuestId(tenantId, guestId));
  }

  /**
   * Lookup by identifier value (FR-014): the value is normalized before matching, a miss is an
   * empty list. Without an explicit type, every type whose normalization accepts the value is
   * tried. ID documents are matched by their hash: pass the value as {@code TYPE:NUMBER}.
   */
  public List<GuestView> lookup(UUID tenantId, String rawValue, IdentifierType type) {
    Set<UUID> guestIds = new LinkedHashSet<>();
    for (NormalizedIdentifier identifier : normalizeForLookup(rawValue, type)) {
      guestIds.addAll(
          identifierRepo.guestIdsByIdentifier(tenantId, identifier.type(), identifier.value()));
    }
    List<GuestView> views = new ArrayList<>();
    for (UUID guestId : guestIds) {
      guestRepo
          .findGuest(tenantId, guestId)
          .ifPresent(entity -> views.add(view(tenantId, mappers.toDomain(entity))));
    }
    return views;
  }

  private List<NormalizedIdentifier> normalizeForLookup(String rawValue, IdentifierType type) {
    List<NormalizedIdentifier> candidates = new ArrayList<>();
    boolean all = type == null;
    if (all || type == IdentifierType.EMAIL) {
      EmailNormalizer.normalize(rawValue)
          .ifPresent(v -> candidates.add(new NormalizedIdentifier(IdentifierType.EMAIL, v)));
    }
    if (all || type == IdentifierType.PHONE) {
      PhoneNormalizer.normalize(rawValue, defaultPhoneRegion)
          .ifPresent(v -> candidates.add(new NormalizedIdentifier(IdentifierType.PHONE, v)));
    }
    String trimmed = rawValue.trim();
    if (!trimmed.isEmpty()) {
      if (all || type == IdentifierType.LOYALTY_ID) {
        candidates.add(new NormalizedIdentifier(IdentifierType.LOYALTY_ID, trimmed));
      }
      if (all || type == IdentifierType.EXTERNAL_KEY) {
        candidates.add(new NormalizedIdentifier(IdentifierType.EXTERNAL_KEY, trimmed));
      }
      if (type == IdentifierType.ID_DOCUMENT && trimmed.contains(":")) {
        String[] parts = trimmed.split(":", 2);
        candidates.add(
            new NormalizedIdentifier(
                IdentifierType.ID_DOCUMENT, IdDocumentHasher.hash(parts[0], parts[1])));
      }
    }
    return candidates;
  }

  private GuestView view(UUID tenantId, Guest guest) {
    return new GuestView(
        guest,
        mappers.toDomainIdentifiers(identifierRepo.findByGuest(tenantId, guest.id())),
        linkRepo.countByGuest(tenantId, guest.id()));
  }
}
