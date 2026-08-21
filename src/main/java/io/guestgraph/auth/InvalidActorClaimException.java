package io.guestgraph.auth;

/**
 * A request tried to record an actor type its credential does not grant (FR-014). A malformed
 * claim, not an authorization failure — hence 400 rather than 403.
 */
public class InvalidActorClaimException extends RuntimeException {

  public InvalidActorClaimException(String detail) {
    super(detail);
  }
}
