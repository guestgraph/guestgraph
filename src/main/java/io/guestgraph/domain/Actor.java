package io.guestgraph.domain;

/**
 * The actor behind one decision (FR-011). {@code type} is fixed by the credential — a request may
 * refine {@code id} to name the individual behind a shared credential, but never widen the type.
 *
 * <p>Events recorded before actor identity existed read back as {@link #unattributed()}, which
 * renders without error rather than failing (FR-015).
 */
public record Actor(ActorType type, String id) {

  private static final Actor UNATTRIBUTED = new Actor(null, null);

  /** Automatic resolution: the deciding matcher's name is the actor's identity (FR-012). */
  public static Actor system(String matcherName) {
    return new Actor(ActorType.SYSTEM, matcherName);
  }

  public static Actor unattributed() {
    return UNATTRIBUTED;
  }

  /** Reassembles a stored actor; a null type is a row predating FR-011, not a bad row. */
  public static Actor of(ActorType type, String id) {
    return type == null ? UNATTRIBUTED : new Actor(type, id);
  }

  public boolean isAttributed() {
    return type != null;
  }
}
