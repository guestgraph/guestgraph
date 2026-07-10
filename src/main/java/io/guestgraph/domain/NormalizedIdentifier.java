package io.guestgraph.domain;

public record NormalizedIdentifier(IdentifierType type, String value)
    implements Comparable<NormalizedIdentifier> {

  @Override
  public int compareTo(NormalizedIdentifier other) {
    int byType = type.compareTo(other.type);
    return byType != 0 ? byType : value.compareTo(other.value);
  }
}
