package com.cctv.platform.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Stores objects in a private S3 bucket (decision D7), used when {@code app.storage.type=s3}.
 * Every object is written with server-side encryption: SSE-KMS when a key id is configured,
 * otherwise SSE-S3 (AES-256). Signed links are S3 presigned GET URLs, so the browser loads media
 * straight from S3 and {@link MediaController} is not used. Created by {@link S3StorageConfig}.
 */
public class S3VideoStorage implements VideoStorage {

    private static final Map<String, String> TYPES = Map.of(
            "mp4", "video/mp4",
            "webm", "video/webm",
            "jpg", "image/jpeg");

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final String kmsKeyId;

    public S3VideoStorage(S3Client s3, S3Presigner presigner, String bucket, String kmsKeyId) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("app.storage.s3.bucket (S3_BUCKET) is required when app.storage.type=s3");
        }
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.kmsKeyId = kmsKeyId == null || kmsKeyId.isBlank() ? null : kmsKeyId;
    }

    @Override
    public long store(String key, InputStream content) throws IOException {
        requireSafe(key);
        // S3 needs the length before the upload starts. The upload stream has none, so it is
        // spooled to a temporary file first (uploads are capped at app.upload.max-bytes).
        Path spool = Files.createTempFile("upload-", ".part");
        try {
            long bytes = Files.copy(content, spool, StandardCopyOption.REPLACE_EXISTING);
            PutObjectRequest.Builder put = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType(key))
                    .contentLength(bytes);
            if (kmsKeyId != null) {
                put.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
            } else {
                put.serverSideEncryption(ServerSideEncryption.AES256);
            }
            try {
                s3.putObject(put.build(), RequestBody.fromFile(spool));
            } catch (S3Exception e) {
                throw new IOException("Could not store the object in S3 (" + e.statusCode() + ")", e);
            }
            return bytes;
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    @Override
    public boolean exists(String key) {
        if (!StorageKeys.isSafe(key)) return false;
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    @Override
    public void delete(String key) throws IOException {
        requireSafe(key);
        // S3 deletes are idempotent: deleting a missing key succeeds.
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            throw new IOException("Could not delete the object from S3 (" + e.statusCode() + ")", e);
        }
    }

    @Override
    public URI signedUrl(String key, Duration ttl) {
        requireSafe(key);
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                // Media may hold identifiable people: the browser must not keep it after the link expires.
                .responseCacheControl("private, max-age=" + ttl.toSeconds())
                .build();
        try {
            return presigner.presignGetObject(r -> r.signatureDuration(ttl).getObjectRequest(get)).url().toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Presigned URL is not a valid URI", e);
        }
    }

    private static void requireSafe(String key) {
        if (!StorageKeys.isSafe(key)) throw new IllegalArgumentException("Unsafe storage key");
    }

    private static String contentType(String key) {
        int dot = key.lastIndexOf('.');
        String ext = dot < 0 ? "" : key.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TYPES.getOrDefault(ext, "application/octet-stream");
    }
}
