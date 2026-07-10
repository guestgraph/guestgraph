package io.guestgraph.api;

import io.guestgraph.auth.TenantContext;
import io.guestgraph.domain.MatchingConfig;
import io.guestgraph.persistence.MatchingConfigService;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-tenant matching thresholds (US4): the three bands' knobs in one resource. Changes apply to
 * subsequent resolutions — no restart, no migration (FR-017).
 */
@RestController
@RequestMapping("/api/v1/config/matching")
public class MatchingConfigController {

  public record ConfigDto(
      @NotNull BigDecimal autoMergeThreshold,
      @NotNull BigDecimal reviewFloor,
      int reviewThreshold) {

    static ConfigDto of(MatchingConfig config) {
      return new ConfigDto(
          config.autoMergeThreshold(), config.reviewFloor(), config.reviewThreshold());
    }
  }

  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final BigDecimal ONE = BigDecimal.ONE;

  private final MatchingConfigService service;

  public MatchingConfigController(MatchingConfigService service) {
    this.service = service;
  }

  @GetMapping
  public ConfigDto get() {
    return ConfigDto.of(service.get(TenantContext.tenantId()));
  }

  @PutMapping
  public ConfigDto update(@RequestBody ConfigDto request) {
    validate(request);
    return ConfigDto.of(
        service.update(
            TenantContext.tenantId(),
            new MatchingConfig(
                request.autoMergeThreshold(), request.reviewFloor(), request.reviewThreshold())));
  }

  /** FR-018: inconsistent values are rejected; the previous configuration stays in effect. */
  private void validate(ConfigDto config) {
    if (config.autoMergeThreshold() == null || config.reviewFloor() == null) {
      throw new BadRequestException("autoMergeThreshold and reviewFloor are required");
    }
    if (config.autoMergeThreshold().compareTo(ZERO) < 0
        || config.autoMergeThreshold().compareTo(ONE) > 0
        || config.reviewFloor().compareTo(ZERO) < 0
        || config.reviewFloor().compareTo(ONE) > 0) {
      throw new BadRequestException("Thresholds must be within 0..1");
    }
    if (config.reviewFloor().compareTo(config.autoMergeThreshold()) > 0) {
      throw new BadRequestException("reviewFloor must not exceed autoMergeThreshold");
    }
    if (config.reviewThreshold() < 1) {
      throw new BadRequestException("reviewThreshold must be >= 1");
    }
  }
}
