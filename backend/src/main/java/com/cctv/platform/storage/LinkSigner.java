package com.cctv.platform.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Signs and checks short-lived media links for {@link LocalVideoStorage}: HMAC-SHA256 over the
 * key and its expiry time. This stands in for S3's presigned URLs, which replace it on AWS.
 */
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
@Component
public class LinkSigner {

    private final byte[] secret;
    private final Clock clock;

    public LinkSigner(@Value("${app.storage.signing-secret}") String secret, Clock clock) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("app.storage.signing-secret (STORAGE_SIGNING_SECRET) must be at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String sign(String key, long expiresEpochSeconds) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal((key + "\n" + expiresEpochSeconds).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** True if the signature matches and the link has not expired. Compared in constant time. */
    public boolean verify(String key, long expiresEpochSeconds, String signature) {
        if (key == null || signature == null || Instant.ofEpochSecond(expiresEpochSeconds).isBefore(clock.instant())) {
            return false;
        }
        return MessageDigest.isEqual(sign(key, expiresEpochSeconds).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }
}
