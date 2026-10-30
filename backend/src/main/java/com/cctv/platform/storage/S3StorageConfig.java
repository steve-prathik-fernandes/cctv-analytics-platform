package com.cctv.platform.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3 storage, active when {@code app.storage.type=s3}. Credentials come from the default AWS
 * chain (on AWS, the task or instance role), never from configuration files. An endpoint can be
 * set for an S3-compatible server such as MinIO in local testing.
 */
@Configuration
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3StorageConfig {

    @Value("${app.storage.s3.region}")
    private String region;

    @Value("${app.storage.s3.endpoint:}")
    private String endpoint;

    @Bean(destroyMethod = "close")
    S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner() {
        var builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    @Bean
    VideoStorage s3VideoStorage(S3Client s3, S3Presigner presigner,
                                @Value("${app.storage.s3.bucket}") String bucket,
                                @Value("${app.storage.s3.kms-key-id:}") String kmsKeyId) {
        return new S3VideoStorage(s3, presigner, bucket, kmsKeyId);
    }
}
