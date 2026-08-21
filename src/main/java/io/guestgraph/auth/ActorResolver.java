package io.guestgraph.auth;

import io.guestgraph.domain.Actor;
import io.guestgraph.domain.ActorType;

/**
 * Binds the actor for the current request (FR-014).
 *
 * <p>The trust boundary is the credential: whether a caller acts as a human or as an agent is
 * decided when the key is issued, never by the request. A request may name the individual behind a
 * shared console credential — useful attribution, but it authorises nothing and is not verified.
 *
 * <p>Requests run on a single (virtual) thread, so a ThreadLocal is enough, exactly as {@link
 * TenantContext} does. The resolution engine deliberately does not read from here: it takes no
 * actor at all, so automatic resolution can only ever be recorded as SYSTEM (FR-012).
 */
public final class ActorResolver {

  public static final String ACTOR_ID_HEADER = "X-Actor-Id";
  public static final String ACTOR_TYPE_HEADER = "X-Actor-Type";

  private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

  private ActorResolver() {}

  static void set(Actor actor) {
    CURRENT.set(actor);
  }

  static void clear() {
    CURRENT.remove();
  }

  public static Actor actor() {
    Actor actor = CURRENT.get();
    if (actor == null) {
      throw new IllegalStateException("No actor bound to the current request");
    }
    return actor;
  }

  /**
   * @param credentialType what the key is registered to act as — the ceiling
   * @param credentialName the key's actor name, used when the request names no individual
   * @param claimedType an {@code X-Actor-Type} header, if the caller sent one
   * @param claimedId an {@code X-Actor-Id} header, if the caller sent one
   * @throws InvalidActorClaimException when the request claims a type its credential does not grant
   */
  public static Actor resolve(
      ActorType credentialType, String credentialName, String claimedType, String claimedId) {
    if (claimedType != null && !claimedType.isBlank()) {
      if (!credentialType.name().equalsIgnoreCase(claimedType.trim())) {
        throw new InvalidActorClaimException(
            "This credential acts as "
                + credentialType
                + " and cannot record a "
                + claimedType.trim()
                + " actor");
      }
    }
    String id = claimedId != null && !claimedId.isBlank() ? claimedId.trim() : credentialName;
    return new Actor(credentialType, id);
  }
}
