package com.cctv.platform.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3 storage against a mocked client. Presigning is real: it is local computation and needs no
 * network, so the tests check the actual URLs the browser would receive.
 */
class S3VideoStorageTests {

    private static final String KEY = "videos/1/2026/10/abc.mp4";

    private final S3Client s3 = mock(S3Client.class);
    private final S3Presigner presigner = S3Presigner.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret")))
            .build();

    @AfterEach
    void close() {
        presigner.close();
    }

    @Test
    void storeUploadsWithSseS3ByDefault() throws IOException {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(PutObjectResponse.builder().build());
        long bytes = storage(null).store(KEY, new ByteArrayInputStream(new byte[1234]));

        assertThat(bytes).isEqualTo(1234);
        PutObjectRequest put = capturedPut();
        assertThat(put.bucket()).isEqualTo("footage");
        assertThat(put.key()).isEqualTo(KEY);
        assertThat(put.contentLength()).isEqualTo(1234);
        assertThat(put.contentType()).isEqualTo("video/mp4");
        assertThat(put.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(put.ssekmsKeyId()).isNull();
    }

    @Test
    void storeUsesSseKmsWhenAKeyIsConfigured() throws IOException {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(PutObjectResponse.builder().build());
        storage("alias/cctv-media").store("clips/1/2/0000.webm", new ByteArrayInputStream(new byte[10]));

        PutObjectRequest put = capturedPut();
        assertThat(put.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(put.ssekmsKeyId()).isEqualTo("alias/cctv-media");
        assertThat(put.contentType()).isEqualTo("video/webm");
    }

    @Test
    void storeFailureIsAnIoException() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("Access Denied").build());
        assertThatThrownBy(() -> storage(null).store(KEY, new ByteArrayInputStream(new byte[1])))
                .isInstanceOf(IOException.class);
    }

    @Test
    void existsIsFalseForMissingObjectsAndUnsafeKeys() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        assertThat(storage(null).exists(KEY)).isTrue();

        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        assertThat(storage(null).exists(KEY)).isFalse();

        assertThat(storage(null).exists("../secret")).isFalse();
    }

    @Test
    void deleteRemovesTheObject() throws IOException {
        storage(null).delete(KEY);
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("footage");
        assertThat(captor.getValue().key()).isEqualTo(KEY);
    }

    @Test
    void signedUrlIsAPresignedGetThatExpires() {
        URI url = storage(null).signedUrl("clips/1/2/0000.mp4", Duration.ofMinutes(5));

        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).isEqualTo("footage.s3.amazonaws.com");
        assertThat(url.getPath()).isEqualTo("/clips/1/2/0000.mp4");
        assertThat(url.getQuery())
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-Expires=300")
                .contains("X-Amz-Signature=")
                .contains("response-cache-control=private, max-age=300");
    }

    @Test
    void unsafeKeysAreRejectedBeforeReachingS3() {
        S3VideoStorage storage = storage(null);
        assertThatThrownBy(() -> storage.store("../etc/passwd", new ByteArrayInputStream(new byte[1])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.signedUrl("/absolute.mp4", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void bucketIsRequired() {
        assertThatThrownBy(() -> new S3VideoStorage(s3, presigner, " ", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3_BUCKET");
    }

    private S3VideoStorage storage(String kmsKeyId) {
        return new S3VideoStorage(s3, presigner, "footage", kmsKeyId);
    }

    private PutObjectRequest capturedPut() {
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        return captor.getValue();
    }
}
