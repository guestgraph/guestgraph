package io.guestgraph.resolution;

import java.util.UUID;

public class ReviewNotFoundException extends RuntimeException {

  public ReviewNotFoundException(UUID reviewId) {
    super("No match review " + reviewId + " in this tenant");
  }
}
