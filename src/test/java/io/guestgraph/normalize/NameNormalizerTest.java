package io.guestgraph.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NameNormalizerTest {

  @Test
  void foldsDiacriticsAndCase() {
    assertThat(NameNormalizer.fold(" Müller ")).isEqualTo("muller");
    assertThat(NameNormalizer.fold("MÜLLER")).isEqualTo("muller");
    assertThat(NameNormalizer.fold("François")).isEqualTo("francois");
    assertThat(NameNormalizer.fold("Ångström")).isEqualTo("angstrom");
  }

  @Test
  void keepsStructureOtherwise() {
    assertThat(NameNormalizer.fold("Anna-Lena")).isEqualTo("anna-lena");
    assertThat(NameNormalizer.fold("van der Berg")).isEqualTo("van der berg");
  }

  @Test
  void nullAndBlankAreNull() {
    assertThat(NameNormalizer.fold(null)).isNull();
    assertThat(NameNormalizer.fold("   ")).isNull();
  }
}
