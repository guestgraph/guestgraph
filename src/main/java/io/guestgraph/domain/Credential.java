package io.guestgraph.domain;

/**
 * What an authenticated API key grants: the tenant it is scoped to, and what it acts as.
 *
 * <p>{@code actorType} is issued with the key and is the ceiling on what a request may record — a
 * request can name the individual behind a shared credential, but never claim a different kind of
 * actor (FR-014).
 */
public record Credential(Tenant tenant, ActorType actorType, String actorName) {}
