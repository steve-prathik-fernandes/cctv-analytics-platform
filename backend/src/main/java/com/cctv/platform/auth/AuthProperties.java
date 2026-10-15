package com.cctv.platform.auth;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Settings under {@code app.auth}. The JWT secret has no default outside the dev profile,
 * so the API refuses to start without one.
 */
@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(
        @NotNull @Size(min = 32, message = "must be at least 32 characters (256 bits for HS256)") String jwtSecret,
        @NotNull Duration tokenTtl,
        @Min(1) int maxFailedLogins,
        @NotNull Duration failedLoginWindow,
        BootstrapAdmin bootstrapAdmin) {

    /** Admin account created on startup when the users table is empty. Blank values skip it. */
    public record BootstrapAdmin(String email, String password) {
    }
}
