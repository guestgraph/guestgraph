package io.guestgraph.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.auth.Sha256;
import org.junit.jupiter.api.Test;

class NormalizerTest {

    // --- Email: trim + lowercase, reject implausible values (R5) ---

    @Test
    void emailIsTrimmedAndLowercased() {
        assertThat(EmailNormalizer.normalize("  Anna.Muster@Example.COM ")).contains("anna.muster@example.com");
    }

    @Test
    void implausibleEmailIsRejectedNotGuessed() {
        assertThat(EmailNormalizer.normalize("not-an-email")).isEmpty();
        assertThat(EmailNormalizer.normalize("a@b")).isEmpty();
        assertThat(EmailNormalizer.normalize("two@@example.com")).isEmpty();
        assertThat(EmailNormalizer.normalize("   ")).isEmpty();
        assertThat(EmailNormalizer.normalize(null)).isEmpty();
    }

    // --- Phone: E.164 via libphonenumber, never guess (R5) ---

    @Test
    void internationalPhoneIsNormalizedToE164() {
        assertThat(PhoneNormalizer.normalize("+41 79 123 45 67", null)).contains("+41791234567");
    }

    @Test
    void nationalPhoneUsesDefaultRegion() {
        assertThat(PhoneNormalizer.normalize("079 123 45 67", "CH")).contains("+41791234567");
    }

    @Test
    void nationalPhoneWithoutRegionIsRejected() {
        assertThat(PhoneNormalizer.normalize("079 123 45 67", null)).isEmpty();
    }

    @Test
    void garbagePhoneIsRejected() {
        assertThat(PhoneNormalizer.normalize("call me maybe", "CH")).isEmpty();
        assertThat(PhoneNormalizer.normalize("+00 00", "CH")).isEmpty();
        assertThat(PhoneNormalizer.normalize(null, "CH")).isEmpty();
    }

    // --- ID document: SHA-256 of TYPE:NUMBER, never plaintext (R5) ---

    @Test
    void idDocumentIsHashedOverNormalizedTypeAndNumber() {
        String expected = Sha256.hex("PASSPORT:X1234567");
        assertThat(IdDocumentHasher.hash(" passport ", "x123 4567")).isEqualTo(expected);
        // Same document in different formatting hashes identically:
        assertThat(IdDocumentHasher.hash("PASSPORT", "X1234567")).isEqualTo(expected);
    }
}
