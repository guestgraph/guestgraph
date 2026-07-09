package io.guestgraph.normalize;

import io.guestgraph.auth.Sha256;

/**
 * ID documents are stored hash-only (constitution: data minimization).
 * Hash input is normalized as TYPE:NUMBER — type uppercased/trimmed, number
 * uppercased with all whitespace removed.
 */
public final class IdDocumentHasher {

    private IdDocumentHasher() {
    }

    public static String hash(String documentType, String documentNumber) {
        String type = documentType.trim().toUpperCase();
        String number = documentNumber.replaceAll("\\s+", "").toUpperCase();
        return Sha256.hex(type + ":" + number);
    }
}
