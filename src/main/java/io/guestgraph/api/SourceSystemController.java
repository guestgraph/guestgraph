package io.guestgraph.api;

import io.guestgraph.auth.TenantContext;
import io.guestgraph.domain.SourceSystem;
import io.guestgraph.persistence.SourceSystemStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/source-systems")
public class SourceSystemController {

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$",
                    message = "must be lowercase kebab-case (a-z, 0-9, -)") String code,
            @NotBlank String name) {
    }

    public record SourceSystemResponse(UUID id, String code, String name, Instant createdAt) {

        static SourceSystemResponse of(SourceSystem sourceSystem) {
            return new SourceSystemResponse(sourceSystem.id(), sourceSystem.code(), sourceSystem.name(),
                    sourceSystem.createdAt());
        }
    }

    private final SourceSystemStore sourceSystemStore;

    public SourceSystemController(SourceSystemStore sourceSystemStore) {
        this.sourceSystemStore = sourceSystemStore;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SourceSystemResponse register(@Valid @RequestBody CreateRequest request) {
        try {
            return SourceSystemResponse.of(
                    sourceSystemStore.insert(TenantContext.tenantId(), request.code(), request.name()));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Source system '" + request.code() + "' is already registered");
        }
    }
}
