package com.cctv.platform.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stores objects as files under {@code app.storage.local-dir}. The detection service reads the
 * same folder (its {@code STORAGE_DIR}), standing in for the shared S3 bucket.
 */
@Component
public class LocalVideoStorage implements VideoStorage {

    private final Path root;

    // Taken as a String: Spring's String-to-Path conversion resolves it as a web resource and
    // rejects relative paths such as ../data/storage.
    public LocalVideoStorage(@Value("${app.storage.local-dir}") String root) throws IOException {
        this.root = Path.of(root).toAbsolutePath().normalize();
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

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Storage key escapes the storage folder");
        }
        return path;
    }
}
