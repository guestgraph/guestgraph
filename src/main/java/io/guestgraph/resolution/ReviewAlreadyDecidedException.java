package io.guestgraph.resolution;

import io.guestgraph.domain.ReviewStatus;
import java.util.UUID;

/** A review is decided exactly once; the first decision stands (FR-018). */
public class ReviewAlreadyDecidedException extends RuntimeException {

  public ReviewAlreadyDecidedException(UUID reviewId, ReviewStatus status) {
    super("Match review " + reviewId + " was already decided (" + status + ")");
  }
}
