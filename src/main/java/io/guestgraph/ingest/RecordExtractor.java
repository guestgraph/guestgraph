package io.guestgraph.ingest;

import io.guestgraph.domain.BlockKey;
import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.NormalizedIdentifier;
import io.guestgraph.normalize.BlockKeys;
import io.guestgraph.normalize.EmailNormalizer;
import io.guestgraph.normalize.IdDocumentHasher;
import io.guestgraph.normalize.PhoneNormalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Extracts normalized fields and strong identifiers from a parsed payload. Malformed values produce
 * reasons (→ needs_review) — never a rejection, never a guess (FR-006, R5). The raw payload itself
 * is stored untouched elsewhere.
 */
@Component
public class RecordExtractor {

  /**
   * All top-level payload fields land in `extracted` except this one: plaintext ID documents are
   * never propagated.
   */
  private static final String ID_DOCUMENT_FIELD = "idDocument";

  private final String defaultPhoneRegion;

  public RecordExtractor(@Value("${guestgraph.default-phone-region:}") String defaultPhoneRegion) {
    this.defaultPhoneRegion =
        defaultPhoneRegion == null || defaultPhoneRegion.isBlank() ? null : defaultPhoneRegion;
  }

  /**
   * @param sourceSystemCode namespaces EXTERNAL_KEY identifiers so different systems' keys never
   *     collide
   */
  public Extraction extract(String sourceSystemCode, Map<String, Object> payload) {
    return extract(sourceSystemCode, payload, List.of());
  }

  /**
   * @param maskedEmailDomains MASKED_ALIAS domains (built-in OTA relays + tenant rules)
   */
  public Extraction extract(
      String sourceSystemCode, Map<String, Object> payload, List<String> maskedEmailDomains) {
    Map<String, Object> extracted = new LinkedHashMap<>();
    List<NormalizedIdentifier> identifiers = new ArrayList<>();
    List<String> reasons = new ArrayList<>();
    List<String> realEmails = new ArrayList<>();
    List<String> phones = new ArrayList<>();
    List<String> maskedEmails = new ArrayList<>();

    payload.forEach(
        (field, value) -> {
          if (!ID_DOCUMENT_FIELD.equals(field)) {
            extracted.put(field, value);
          }
        });

    // After the raw copy, so the canonical ISO form wins (like email/phone below).
    LocalDate birthdate = parseBirthdate(payload, extracted, reasons);

    asText(payload.get("email"))
        .ifPresent(
            raw -> {
              Optional<String> email = EmailNormalizer.normalize(raw);
              if (email.isPresent()) {
                boolean masked =
                    maskedEmailDomains.stream().anyMatch(d -> email.get().endsWith("@" + d));
                extracted.put("email", email.get());
                if (masked) {
                  // MASKED_ALIAS: no EMAIL identifier (no deterministic merges), flagged
                  // for the survivorship guard, EMAIL_MASKED block key only (US3).
                  extracted.put("emailMasked", true);
                  maskedEmails.add(email.get());
                } else {
                  identifiers.add(new NormalizedIdentifier(IdentifierType.EMAIL, email.get()));
                  realEmails.add(email.get());
                }
              } else {
                reasons.add("email: not a valid email address");
              }
            });

    asText(payload.get("phone"))
        .ifPresent(
            raw -> {
              Optional<String> phone = PhoneNormalizer.normalize(raw, defaultPhoneRegion);
              if (phone.isPresent()) {
                extracted.put("phone", phone.get());
                identifiers.add(new NormalizedIdentifier(IdentifierType.PHONE, phone.get()));
                phones.add(phone.get());
              } else {
                reasons.add("phone: cannot be normalized to E.164");
              }
            });

    asText(payload.get("loyaltyId"))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .ifPresent(
            loyalty ->
                identifiers.add(new NormalizedIdentifier(IdentifierType.LOYALTY_ID, loyalty)));

    asText(payload.get("externalGuestId"))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .ifPresent(
            key ->
                identifiers.add(
                    new NormalizedIdentifier(
                        IdentifierType.EXTERNAL_KEY, sourceSystemCode + ":" + key)));

    Object idDocument = payload.get(ID_DOCUMENT_FIELD);
    if (idDocument instanceof Map<?, ?> doc) {
      Optional<String> type = asText(doc.get("type"));
      Optional<String> number = asText(doc.get("number"));
      if (type.isPresent() && number.isPresent()) {
        identifiers.add(
            new NormalizedIdentifier(
                IdentifierType.ID_DOCUMENT, IdDocumentHasher.hash(type.get(), number.get())));
      } else {
        reasons.add("idDocument: requires both type and number");
      }
    } else if (idDocument != null) {
      reasons.add("idDocument: expected an object with type and number");
    }

    if (identifiers.isEmpty()) {
      reasons.add("no valid strong identifiers in record");
    }
    List<BlockKey> blockKeys =
        BlockKeys.derive(
            new BlockKeys.PersonFields(
                asText(payload.get("firstName")).orElse(null),
                asText(payload.get("lastName")).orElse(null),
                birthdate,
                phones,
                realEmails,
                maskedEmails));
    return new Extraction(extracted, identifiers, blockKeys, reasons);
  }

  private LocalDate parseBirthdate(
      Map<String, Object> payload, Map<String, Object> extracted, List<String> reasons) {
    Optional<String> raw = asText(payload.get("birthdate"));
    if (raw.isEmpty()) {
      return null;
    }
    try {
      LocalDate parsed = LocalDate.parse(raw.get().trim());
      extracted.put("birthdate", parsed.toString());
      return parsed;
    } catch (DateTimeParseException e) {
      reasons.add("birthdate: not an ISO date");
      return null;
    }
  }

  private Optional<String> asText(Object value) {
    if (value instanceof String s && !s.isBlank()) {
      return Optional.of(s);
    }
    return Optional.empty();
  }

  public record Extraction(
      Map<String, Object> extracted,
      List<NormalizedIdentifier> identifiers,
      List<BlockKey> blockKeys,
      List<String> reasons) {

    public boolean needsReview() {
      return !reasons.isEmpty();
    }
  }
}
