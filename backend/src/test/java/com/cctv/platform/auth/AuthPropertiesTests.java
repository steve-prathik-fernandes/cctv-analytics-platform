package com.cctv.platform.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** The API must refuse to start without a usable JWT secret, rather than fall back to a weak one. */
class AuthPropertiesTests {

    @Configuration
    @EnableConfigurationProperties(AuthProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("app.auth.token-ttl=60m", "app.auth.max-failed-logins=5",
                    "app.auth.failed-login-window=15m");

    @Test
    void missingSecretFailsStartup() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void shortSecretFailsStartup() {
        runner.withPropertyValues("app.auth.jwt-secret=too-short")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("at least 32"));
    }

    @Test
    void longSecretIsAccepted() {
        runner.withPropertyValues("app.auth.jwt-secret=" + "x".repeat(32))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
