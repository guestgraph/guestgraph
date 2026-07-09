package io.guestgraph.normalize;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.util.Optional;

/**
 * E.164 normalization via libphonenumber. Numbers that cannot be parsed and
 * validated are rejected (→ needs_review), never guessed (research R5).
 */
public final class PhoneNormalizer {

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private PhoneNormalizer() {
    }

    /** @param defaultRegion ISO region for national-format numbers; null accepts only +international */
    public static Optional<String> normalize(String raw, String defaultRegion) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Phonenumber.PhoneNumber number = UTIL.parse(raw.trim(), defaultRegion);
            if (!UTIL.isValidNumber(number)) {
                return Optional.empty();
            }
            return Optional.of(UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }
}
