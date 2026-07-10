package io.guestgraph.normalize;

import java.text.Normalizer;
import java.util.Locale;

/** Diacritic folding + lowercasing for name comparison and phonetic keying (R2-1). */
public final class NameNormalizer {

  private NameNormalizer() {}

  public static String fold(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD);
    return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
  }
}
