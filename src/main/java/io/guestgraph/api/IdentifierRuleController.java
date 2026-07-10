package io.guestgraph.api;

import io.guestgraph.auth.TenantContext;
import io.guestgraph.domain.IdentifierQualityRule;
import io.guestgraph.domain.IdentifierType;
import io.guestgraph.domain.RuleEffect;
import io.guestgraph.domain.RuleMatchKind;
import io.guestgraph.persistence.IdentifierRuleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Identifier quality rules (US3): tenant CRUD plus the read-only built-in OTA defaults. */
@RestController
@RequestMapping("/api/v1/config/identifier-rules")
public class IdentifierRuleController {

  public record CreateRequest(
      @NotNull IdentifierType identifierType,
      @NotNull RuleMatchKind matchKind,
      @NotBlank String value,
      @NotNull RuleEffect rule,
      String note) {}

  public record RuleDto(
      UUID id,
      String identifierType,
      String matchKind,
      String value,
      String rule,
      String note,
      boolean builtin,
      Instant createdAt) {

    static RuleDto of(IdentifierQualityRule rule) {
      return new RuleDto(
          rule.id(),
          rule.identifierType().name(),
          rule.matchKind().name(),
          rule.valueNormalized(),
          rule.rule().name(),
          rule.note(),
          rule.builtin(),
          rule.createdAt());
    }
  }

  private final IdentifierRuleService service;

  public IdentifierRuleController(IdentifierRuleService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> list() {
    return Map.of(
        "rules", service.list(TenantContext.tenantId()).stream().map(RuleDto::of).toList());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RuleDto add(@Valid @RequestBody CreateRequest request) {
    if (request.matchKind() == RuleMatchKind.EMAIL_DOMAIN
        && request.identifierType() != IdentifierType.EMAIL) {
      throw new BadRequestException("EMAIL_DOMAIN rules require identifierType EMAIL");
    }
    if (request.rule() == RuleEffect.MASKED_ALIAS
        && request.matchKind() != RuleMatchKind.EMAIL_DOMAIN) {
      throw new BadRequestException("MASKED_ALIAS rules require matchKind EMAIL_DOMAIN");
    }
    String value = request.value().trim().toLowerCase(Locale.ROOT);
    try {
      return RuleDto.of(
          service.add(
              new IdentifierQualityRule(
                  null,
                  TenantContext.tenantId(),
                  request.identifierType(),
                  request.matchKind(),
                  value,
                  request.rule(),
                  request.note(),
                  false,
                  null)));
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException("An equivalent rule already exists");
    }
  }

  @DeleteMapping("/{ruleId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID ruleId) {
    // Built-ins have no id and no row — any id that misses is either foreign or builtin.
    if (!service.delete(TenantContext.tenantId(), ruleId)) {
      throw new NotFoundException("No deletable identifier rule " + ruleId + " in this tenant");
    }
  }
}
