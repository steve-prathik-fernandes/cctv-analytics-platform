package com.cctv.platform.job;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Submits jobs to the detection service: {@code POST /jobs} (docs/03-architecture.md, section 4). */
@Component
public class DetectionClient {

    private final RestClient http;

    public DetectionClient(DetectionProperties props, RestClient.Builder builder) {
        this.http = builder
                .baseUrl(props.baseUrl())
                .defaultHeader("X-Service-Token", props.serviceToken())
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(5))
                        .withReadTimeout(Duration.ofSeconds(15))))
                .build();
    }

    public record ZonePayload(long id, String name, List<List<Double>> polygon) {
    }

    public record RulePayload(long id, String type, Long zoneId, Map<String, Object> params) {
    }

    public record JobPayload(long jobId, long cameraId, String videoKey, Instant recordedStartAt, int frameStride,
                             double minConfidence, List<ZonePayload> zones, List<RulePayload> rules) {
    }

    /** Throws if the service is unreachable or refuses the job. */
    public void submit(JobPayload job) {
        http.post().uri("/jobs").contentType(MediaType.APPLICATION_JSON).body(job).retrieve().toBodilessEntity();
    }
}
