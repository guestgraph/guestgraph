package io.guestgraph.domain;

/** A similarity-oriented derivative of a record — the means of finding fuzzy candidates. */
public record BlockKey(BlockKeyType type, String value) implements Comparable<BlockKey> {

  @Override
  public int compareTo(BlockKey other) {
    int byType = type.compareTo(other.type);
    return byType != 0 ? byType : value.compareTo(other.value);
  }
}
