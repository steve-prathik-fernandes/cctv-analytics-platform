package com.cctv.platform.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Stores objects as files under {@code app.storage.local-dir}. The detection service reads the
 * same folder (its {@code STORAGE_DIR}), standing in for the shared S3 bucket. Signed links
 * point to {@link MediaController}.
 */
@Component
public class LocalVideoStorage implements VideoStorage {

    private final Path root;
    private final LinkSigner signer;
    private final Clock clock;

    // Taken as a String: Spring's String-to-Path conversion resolves it as a web resource and
    // rejects relative paths such as ../data/storage.
    public LocalVideoStorage(@Value("${app.storage.local-dir}") String root, LinkSigner signer, Clock clock)
            throws IOException {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.signer = signer;
        this.clock = clock;
        Files.createDirectories(this.root);
    }

    @Override
    public long store(String key, InputStream content) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        // Write to a temporary name first so a half-written upload is never visible under the key.
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            long bytes = Files.copy(content, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            return bytes;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    @Override
    public URI signedUrl(String key, Duration ttl) {
        long expires = clock.instant().plus(ttl).getEpochSecond();
        return UriComponentsBuilder.fromPath(MediaController.PATH)
                .queryParam("key", key)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(key, expires))
                .encode()
                .build()
                .toUri();
    }

    /** The file behind a key, if it exists and the key is safe. */
    Optional<Path> file(String key) {
        if (!StorageKeys.isSafe(key)) return Optional.empty();
        Path path = resolve(key);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Storage key escapes the storage folder");
        }
        return path;
    }
}
