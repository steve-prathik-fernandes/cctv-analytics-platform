package com.cctv.platform.storage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code app.storage.type=s3} the API uses S3 and drops the local media endpoint and its
 * signing secret. No AWS call is made: clients are created lazily and nothing is stored here.
 */
@SpringBootTest(properties = {
        "app.storage.type=s3",
        "app.storage.s3.bucket=test-footage",
        "app.storage.s3.region=us-east-1"})
@ActiveProfiles("dev")
class S3StorageWiringTests {

    @Autowired ApplicationContext context;

    @Test
    void s3StorageReplacesLocalStorage() {
        assertThat(context.getBean(VideoStorage.class)).isInstanceOf(S3VideoStorage.class);
        assertThat(context.getBeanNamesForType(LocalVideoStorage.class)).isEmpty();
        assertThat(context.getBeanNamesForType(MediaController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LinkSigner.class)).isEmpty();
    }
}
