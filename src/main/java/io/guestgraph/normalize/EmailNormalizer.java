package io.guestgraph.normalize;

import java.util.Optional;
import java.util.regex.Pattern;

/** Trim + lowercase; empty when the value is not a plausible email address (research R5). */
public final class EmailNormalizer {

    private static final Pattern PLAUSIBLE_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private EmailNormalizer() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase();
        return PLAUSIBLE_EMAIL.matcher(normalized).matches() ? Optional.of(normalized) : Optional.empty();
    }
}
