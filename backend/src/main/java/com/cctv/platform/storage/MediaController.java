package com.cctv.platform.storage;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Serves stored media through signed links from {@link LocalVideoStorage#signedUrl}. The
 * signature is the authorization: no login token is needed, so {@code <img>} and
 * {@code <video>} can load it, and the link stops working when it expires. On AWS, S3
 * presigned URLs take this role and this endpoint is unused. Video supports range requests
 * (seeking) through Spring's {@link Resource} handling.
 */
@RestController
public class MediaController {

    static final String PATH = "/api/media";

    private static final Map<String, MediaType> TYPES = Map.of(
            "jpg", MediaType.IMAGE_JPEG,
            "mp4", MediaType.parseMediaType("video/mp4"),
            "webm", MediaType.parseMediaType("video/webm"));

    private final LocalVideoStorage storage;
    private final LinkSigner signer;
    private final Clock clock;

    public MediaController(LocalVideoStorage storage, LinkSigner signer, Clock clock) {
        this.storage = storage;
        this.signer = signer;
        this.clock = clock;
    }

    @GetMapping(PATH)
    public ResponseEntity<Resource> get(@RequestParam String key, @RequestParam long expires,
                                        @RequestParam String sig) {
        // One answer for every failure, so the endpoint reveals nothing about which keys exist.
        if (!signer.verify(key, expires, sig)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        MediaType type = TYPES.get(extension(key));
        if (type == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        Path file = storage.file(key).orElse(null);
        if (file == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        long remaining = Math.max(0, Duration.between(clock.instant(), Instant.ofEpochSecond(expires)).toSeconds());
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(remaining)).cachePrivate())
                .body(new FileSystemResource(file));
    }

    private static String extension(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? "" : key.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
