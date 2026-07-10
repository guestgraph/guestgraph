package io.guestgraph.domain;

public enum MergeEventKind {
  CREATE,
  ATTACH,
  MERGE,
  UNMERGE,
  REVIEW_CONFIRM,
  REVIEW_REJECT
}
