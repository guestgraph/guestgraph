package io.guestgraph.ingest;

import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.normalize.EmailNormalizer;
import io.guestgraph.normalize.IdDocumentHasher;
import io.guestgraph.normalize.PhoneNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Extracts normalized fields and strong identifiers from a parsed payload.
 * Malformed values produce reasons (→ needs_review) — never a rejection, never a
 * guess (FR-006, R5). The raw payload itself is stored untouched elsewhere.
 */
@Component
public class RecordExtractor {

    /** All top-level payload fields land in `extracted` except this one: plaintext ID documents are never propagated. */
    private static final String ID_DOCUMENT_FIELD = "idDocument";

    private final String defaultPhoneRegion;

    public RecordExtractor(@Value("${guestgraph.default-phone-region:}") String defaultPhoneRegion) {
        this.defaultPhoneRegion = defaultPhoneRegion == null || defaultPhoneRegion.isBlank()
                ? null : defaultPhoneRegion;
    }

    /** @param sourceSystemCode namespaces EXTERNAL_KEY identifiers so different systems' keys never collide */
    public Extraction extract(String sourceSystemCode, Map<String, Object> payload) {
        Map<String, Object> extracted = new LinkedHashMap<>();
        List<NormalizedIdentifier> identifiers = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        payload.forEach((field, value) -> {
            if (!ID_DOCUMENT_FIELD.equals(field)) {
                extracted.put(field, value);
            }
        });

        asText(payload.get("email")).ifPresent(raw -> {
            Optional<String> email = EmailNormalizer.normalize(raw);
            if (email.isPresent()) {
                extracted.put("email", email.get());
                identifiers.add(new NormalizedIdentifier(IdentifierType.EMAIL, email.get()));
            } else {
                reasons.add("email: not a valid email address");
            }
        });

        asText(payload.get("phone")).ifPresent(raw -> {
            Optional<String> phone = PhoneNormalizer.normalize(raw, defaultPhoneRegion);
            if (phone.isPresent()) {
                extracted.put("phone", phone.get());
                identifiers.add(new NormalizedIdentifier(IdentifierType.PHONE, phone.get()));
            } else {
                reasons.add("phone: cannot be normalized to E.164");
            }
        });

        asText(payload.get("loyaltyId"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .ifPresent(loyalty -> identifiers.add(new NormalizedIdentifier(IdentifierType.LOYALTY_ID, loyalty)));

        asText(payload.get("externalGuestId"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .ifPresent(key -> identifiers.add(new NormalizedIdentifier(IdentifierType.EXTERNAL_KEY,
                        sourceSystemCode + ":" + key)));

        Object idDocument = payload.get(ID_DOCUMENT_FIELD);
        if (idDocument instanceof Map<?, ?> doc) {
            Optional<String> type = asText(doc.get("type"));
            Optional<String> number = asText(doc.get("number"));
            if (type.isPresent() && number.isPresent()) {
                identifiers.add(new NormalizedIdentifier(IdentifierType.ID_DOCUMENT,
                        IdDocumentHasher.hash(type.get(), number.get())));
            } else {
                reasons.add("idDocument: requires both type and number");
            }
        } else if (idDocument != null) {
            reasons.add("idDocument: expected an object with type and number");
        }

        if (identifiers.isEmpty()) {
            reasons.add("no valid strong identifiers in record");
        }
        return new Extraction(extracted, identifiers, reasons);
    }

    private Optional<String> asText(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            return Optional.of(s);
        }
        return Optional.empty();
    }

    public record Extraction(Map<String, Object> extracted, List<NormalizedIdentifier> identifiers,
            List<String> reasons) {

        public boolean needsReview() {
            return !reasons.isEmpty();
        }
    }
}
