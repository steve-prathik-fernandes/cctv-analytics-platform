package com.cctv.platform.job;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code app.detection}. The service token is shared with the detection service
 * (its SERVICE_TOKEN) and authenticates calls in both directions. It has no default outside the
 * dev profile, so the API refuses to start without one.
 */
@Validated
@ConfigurationProperties("app.detection")
public record DetectionProperties(
        @NotBlank String baseUrl,
        @NotNull @Size(min = 32, message = "must be at least 32 characters") String serviceToken,
        @NotBlank String modelName,
        @Min(1) @Max(60) int frameStride,
        @DecimalMin("0.05") @DecimalMax("0.95") double minConfidence) {
}
