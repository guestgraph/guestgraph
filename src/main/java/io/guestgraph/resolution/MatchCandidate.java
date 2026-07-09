package io.guestgraph.resolution;

import io.guestgraph.domain.NormalizedIdentifier;
import java.util.UUID;

/** A guest that shares one identifier with the record being resolved. */
public record MatchCandidate(UUID guestId, NormalizedIdentifier identifier, int recordsSharingIdentifier) {
}
