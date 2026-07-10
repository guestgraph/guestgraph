package io.guestgraph.normalize;

import io.guestgraph.domain.BlockKey;
import io.guestgraph.domain.BlockKeyType;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.codec.language.DoubleMetaphone;

/**
 * Derives the similarity-oriented blocking keys of a record (data-model.md). Keys are only emitted
 * when all their inputs are present — never a partial/garbage key.
 */
public final class BlockKeys {

  private static final DoubleMetaphone PHONETIC = new DoubleMetaphone();

  public record PersonFields(
      String firstName,
      String lastName,
      LocalDate birthdate,
      List<String> phonesE164,
      List<String> realEmails,
      List<String> maskedEmails) {}

  private BlockKeys() {}

  public static List<BlockKey> derive(PersonFields fields) {
    Set<BlockKey> keys = new LinkedHashSet<>();
    String first = NameNormalizer.fold(fields.firstName());
    String last = NameNormalizer.fold(fields.lastName());

    if (last != null && fields.birthdate() != null) {
      String phonetic = PHONETIC.doubleMetaphone(last);
      if (phonetic != null && !phonetic.isBlank()) {
        keys.add(
            new BlockKey(
                BlockKeyType.NAME_PHONETIC_BIRTHYEAR,
                phonetic + ":" + fields.birthdate().getYear()));
      }
    }
    if (first != null && last != null && fields.birthdate() != null) {
      // Sorted initials: swapped first/last name order still blocks together.
      char a = first.charAt(0);
      char b = last.charAt(0);
      String initials = a <= b ? "" + a + b : "" + b + a;
      keys.add(
          new BlockKey(BlockKeyType.NAME_INITIALS_BIRTHDATE, initials + ":" + fields.birthdate()));
    }
    for (String phone : fields.phonesE164()) {
      String digits = phone.replaceAll("\\D", "");
      if (digits.length() >= 7) {
        keys.add(new BlockKey(BlockKeyType.PHONE_SUFFIX7, digits.substring(digits.length() - 7)));
      }
    }
    for (String email : fields.realEmails()) {
      int at = email.indexOf('@');
      if (at > 0) {
        keys.add(new BlockKey(BlockKeyType.EMAIL_LOCALPART, email.substring(0, at)));
      }
    }
    for (String masked : fields.maskedEmails()) {
      keys.add(new BlockKey(BlockKeyType.EMAIL_MASKED, masked));
    }
    return List.copyOf(keys);
  }
}
