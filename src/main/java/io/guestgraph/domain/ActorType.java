package io.guestgraph.domain;

/**
 * Who caused a decision. {@code SYSTEM} is the engine acting on its own rules; {@code HUMAN} and
 * {@code AGENT} come from the authenticated credential and can never be claimed by a request
 * (FR-012, FR-014).
 */
public enum ActorType {
  SYSTEM,
  HUMAN,
  AGENT
}
