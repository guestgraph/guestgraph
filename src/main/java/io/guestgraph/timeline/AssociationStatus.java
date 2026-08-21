package io.guestgraph.timeline;

/**
 * Whether a guest still holds an association. {@code CURRENT} while the object's newest roster
 * places the guest in that role; {@code ENDED} once it no longer does — whether the role passed to
 * someone else or the guest was dropped from the object entirely (FR-007).
 */
public enum AssociationStatus {
  CURRENT,
  ENDED
}
