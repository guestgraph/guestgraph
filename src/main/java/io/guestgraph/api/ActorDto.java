package io.guestgraph.api;

import io.guestgraph.domain.Actor;

/**
 * Who caused a decision, as the API renders it. Null for events recorded before actor identity
 * existed — unattributed reads cleanly rather than failing (FR-015).
 */
public record ActorDto(String type, String id) {

  static ActorDto of(Actor actor) {
    return actor == null || !actor.isAttributed()
        ? null
        : new ActorDto(actor.type().name(), actor.id());
  }
}
