package io.guestgraph.domain;

/** Per-record outcome of an ingest (contracts/openapi.yaml IngestResult.status). */
public enum IngestStatus {
  CREATED_GUEST,
  ATTACHED,
  MERGED,
  DUPLICATE_IGNORED,
  ERROR
}
