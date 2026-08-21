package io.guestgraph.domain;

/**
 * The capacity in which a person appears on a business object. The role is part of an association's
 * identity; the position a source listed the person at is not (FR-003) — persons are never matched
 * across object versions, so a shifted position is not a reassignment.
 */
public enum ObjectRole {
  PRIMARY_GUEST,
  ADDITIONAL_GUEST,
  BOOKER
}
