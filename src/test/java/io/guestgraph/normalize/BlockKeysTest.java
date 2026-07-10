package io.guestgraph.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.domain.BlockKey;
import io.guestgraph.domain.BlockKeyType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockKeysTest {

  @Test
  void phoneticKeyRequiresLastNameAndBirthdate_andEquatesSpellingVariants() {
    List<BlockKey> mueller = derive("Anna", "Müller", LocalDate.of(1985, 3, 12));
    List<BlockKey> muellerAscii = derive("Anna", "Mueller", LocalDate.of(1985, 3, 12));

    BlockKey phonetic = keyOf(mueller, BlockKeyType.NAME_PHONETIC_BIRTHYEAR);
    assertThat(phonetic.value()).endsWith(":1985");
    // The whole point of the phonetic key: spelling variants block together.
    assertThat(keyOf(muellerAscii, BlockKeyType.NAME_PHONETIC_BIRTHYEAR)).isEqualTo(phonetic);
    // Missing either part → no key (never a garbage key).
    assertThat(keys(derive("Anna", "Müller", null), BlockKeyType.NAME_PHONETIC_BIRTHYEAR))
        .isEmpty();
    assertThat(
            keys(
                derive("Anna", null, LocalDate.of(1985, 3, 12)),
                BlockKeyType.NAME_PHONETIC_BIRTHYEAR))
        .isEmpty();
  }

  @Test
  void initialsKeyCombinesFoldedInitialsWithFullBirthdate() {
    List<BlockKey> keys = derive("Anna", "Müller", LocalDate.of(1985, 3, 12));

    assertThat(keyOf(keys, BlockKeyType.NAME_INITIALS_BIRTHDATE).value())
        .isEqualTo("am:1985-03-12");
  }

  @Test
  void phoneSuffixTakesLastSevenDigits() {
    List<BlockKey> keys =
        BlockKeys.derive(
            new BlockKeys.PersonFields(
                "Anna", "Müller", null, List.of("+41791234567"), List.of(), List.of()));

    assertThat(keyOf(keys, BlockKeyType.PHONE_SUFFIX7).value()).isEqualTo("1234567");
  }

  @Test
  void emailLocalPartOnlyForRealEmails_maskedGetFullAliasKey() {
    List<BlockKey> keys =
        BlockKeys.derive(
            new BlockKeys.PersonFields(
                "Anna",
                "Müller",
                null,
                List.of(),
                List.of("anna.m@example.com"),
                List.of("x1@guest.booking.com")));

    assertThat(keyOf(keys, BlockKeyType.EMAIL_LOCALPART).value()).isEqualTo("anna.m");
    assertThat(keyOf(keys, BlockKeyType.EMAIL_MASKED).value()).isEqualTo("x1@guest.booking.com");
    // The masked alias must NOT contribute a local-part key (it is not a real address).
    assertThat(keys(keys, BlockKeyType.EMAIL_LOCALPART)).hasSize(1);
  }

  @Test
  void noFieldsNoKeys() {
    assertThat(
            BlockKeys.derive(
                new BlockKeys.PersonFields(null, null, null, List.of(), List.of(), List.of())))
        .isEmpty();
  }

  private List<BlockKey> derive(String first, String last, LocalDate birthdate) {
    return BlockKeys.derive(
        new BlockKeys.PersonFields(first, last, birthdate, List.of(), List.of(), List.of()));
  }

  private BlockKey keyOf(List<BlockKey> keys, BlockKeyType type) {
    return keys.stream().filter(k -> k.type() == type).findFirst().orElseThrow();
  }

  private List<BlockKey> keys(List<BlockKey> keys, BlockKeyType type) {
    return keys.stream().filter(k -> k.type() == type).toList();
  }
}
