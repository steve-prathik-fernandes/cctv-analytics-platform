package com.cctv.platform;

import com.cctv.platform.audit.AuditLogRepository;
import com.cctv.platform.camera.Camera;
import com.cctv.platform.camera.CameraRepository;
import com.cctv.platform.event.EventRepository;
import com.cctv.platform.job.DetectionClient;
import com.cctv.platform.job.DetectionClient.JobPayload;
import com.cctv.platform.job.ProcessingJob;
import com.cctv.platform.job.ProcessingJobRepository;
import com.cctv.platform.user.Role;
import com.cctv.platform.user.User;
import com.cctv.platform.user.UserRepository;
import com.cctv.platform.video.VideoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Upload, job dispatch, detection callbacks, event search, deletion, zones and rules. */
@SpringBootTest(properties = "app.upload.max-bytes=1048576")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class ProcessingFlowTests {

    static final String PASSWORD = "correct-horse-battery";
    static final String SERVICE_TOKEN = "dev-only-service-token-change-me-0123456789";
    /** Smallest prefix that passes the MP4 check: a box size, then "ftyp". */
    static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0};

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-dir", () -> storageDir.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired CameraRepository cameras;
    @Autowired VideoRepository videos;
    @Autowired ProcessingJobRepository jobs;
    @Autowired EventRepository events;
    @Autowired AuditLogRepository auditLogs;
    @Autowired PasswordEncoder passwordEncoder;
    @MockBean DetectionClient detection;

    User admin;
    User operator;
    Camera camera;
    Camera otherCamera;

    @BeforeEach
    void setUp() {
        admin = createUser(Role.ADMIN);
        operator = createUser(Role.OPERATOR);
        camera = createCamera("Dock");
        otherCamera = createCamera("Gate");
        operator.getCameras().add(camera);
        users.saveAndFlush(operator);
    }

    // --- Upload ---

    @Test
    void uploadStoresFileUnderGeneratedKeyAndDispatchesJob() throws Exception {
        long zoneId = createZone(camera.getId());
        createRule(camera.getId(), Map.of("type", "LOITERING", "zoneId", zoneId, "params", Map.of("minSeconds", 30)));
        createRule(camera.getId(), Map.of("type", "ZONE_ENTRY", "zoneId", zoneId, "params", Map.of(),
                "enabled", false));

        JsonNode body = read(upload(operator, camera.getId(), "My Clip.mp4", MP4, "2026-10-04T21:30:00Z")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.video.originalFilename").value("My Clip.mp4"))
                .andExpect(jsonPath("$.job.status").value("QUEUED")));

        ArgumentCaptor<JobPayload> sent = ArgumentCaptor.forClass(JobPayload.class);
        verify(detection).submit(sent.capture());
        JobPayload payload = sent.getValue();
        assertThat(payload.jobId()).isEqualTo(body.at("/job/id").asLong());
        assertThat(payload.videoKey()).matches("videos/" + camera.getId() + "/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.mp4");
        assertThat(payload.recordedStartAt()).isEqualTo(Instant.parse("2026-10-04T21:30:00Z"));
        assertThat(payload.zones()).hasSize(1);
        assertThat(payload.rules()).extracting(DetectionClient.RulePayload::type).containsExactly("LOITERING");
        assertThat(storageDir.resolve(payload.videoKey())).exists();
        assertThat(lastAuditAction()).isEqualTo("VIDEO_UPLOAD");
    }

    @Test
    void unreachableDetectionServiceMarksJobFailed() throws Exception {
        doThrow(new IllegalStateException("connection refused")).when(detection).submit(any());
        upload(operator, camera.getId(), "a.mp4", MP4, "2026-10-04T21:30:00Z")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job.status").value("FAILED"))
                .andExpect(jsonPath("$.job.error").value(org.hamcrest.Matchers.containsString("could not be reached")));
    }

    @Test
    void renamedNonVideoIsRejectedAndNothingStored() throws Exception {
        long before = countStoredFiles();
        upload(operator, camera.getId(), "evil.mp4", "<?php echo 1; ?>".getBytes(), "2026-10-04T21:30:00Z")
                .andExpect(status().isUnsupportedMediaType());
        assertThat(countStoredFiles()).isEqualTo(before);
        assertThat(videos.count()).isZero();
        verify(detection, never()).submit(any());
    }

    @Test
    void wrongExtensionIsRejected() throws Exception {
        upload(operator, camera.getId(), "clip.avi", MP4, "2026-10-04T21:30:00Z")
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void fileOverLimitIsRejected() throws Exception {
        byte[] big = new byte[1_048_577];
        System.arraycopy(MP4, 0, big, 0, MP4.length);
        upload(operator, camera.getId(), "big.mp4", big, "2026-10-04T21:30:00Z")
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void recordedTimeInFutureIsRejected() throws Exception {
        upload(operator, camera.getId(), "a.mp4", MP4, Instant.now().plusSeconds(86_400).toString())
                .andExpect(status().isBadRequest());
    }

    @Test
    void pathInFileNameIsNeverUsed() throws Exception {
        upload(operator, camera.getId(), "../../etc/passwd.mp4", MP4, "2026-10-04T21:30:00Z")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.video.originalFilename").value("passwd.mp4"));
    }

    @Test
    void operatorCannotUploadToUnassignedCamera() throws Exception {
        upload(operator, otherCamera.getId(), "a.mp4", MP4, "2026-10-04T21:30:00Z")
                .andExpect(status().isNotFound());
        verify(detection, never()).submit(any());
    }

    // --- Detection service callbacks ---

    @Test
    void callbacksRequireServiceToken() throws Exception {
        long jobId = uploadJob();
        mvc.perform(post("/internal/jobs/" + jobId + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RUNNING\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/jobs/" + jobId + "/status").header("X-Service-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RUNNING\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void runningThenEventsMarksJobDoneWithAbsoluteTimes() throws Exception {
        long jobId = uploadJob();
        internal(jobId, "status", "{\"status\":\"RUNNING\"}").andExpect(status().isNoContent());
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(ProcessingJob.Status.RUNNING);

        internal(jobId, "events", events(event("OBJECT_DETECTED", "person", 0.9, 1500, 4000),
                event("OBJECT_DETECTED", "car", 0.7, 0, 2000))).andExpect(status().isNoContent());

        mvc.perform(get("/api/jobs/" + jobId).header("Authorization", bearer(operator)))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.eventCount").value(2));
        var stored = events.findByJobIdOrderByStartedAt(jobId);
        assertThat(stored.get(1).getStartedAt()).isEqualTo(Instant.parse("2026-10-04T21:30:01.500Z"));
        assertThat(stored.get(1).getEndedAt()).isEqualTo(Instant.parse("2026-10-04T21:30:04Z"));
        assertThat(stored.get(1).getCameraId()).isEqualTo(camera.getId());
    }

    @Test
    void resendingEventsReplacesThem() throws Exception {
        long jobId = uploadJob();
        internal(jobId, "events", events(event("OBJECT_DETECTED", "person", 0.9, 0, 1000),
                event("OBJECT_DETECTED", "person", 0.8, 0, 1000))).andExpect(status().isNoContent());
        internal(jobId, "events", events(event("OBJECT_DETECTED", "car", 0.9, 0, 1000)))
                .andExpect(status().isNoContent());
        assertThat(events.findByJobIdOrderByStartedAt(jobId)).hasSize(1);
    }

    @Test
    void invalidEventsAreRejected() throws Exception {
        long jobId = uploadJob();
        internal(jobId, "events", events(event("OBJECT_DETECTED", "dog", 0.9, 0, 1000))).andExpect(status().isBadRequest());
        internal(jobId, "events", events(event("OBJECT_DETECTED", "person", 1.5, 0, 1000))).andExpect(status().isBadRequest());
        internal(jobId, "events", events(event("OBJECT_DETECTED", "person", 0.9, 2000, 1000))).andExpect(status().isBadRequest());
        internal(jobId, "events", events(Map.of("eventType", "OBJECT_DETECTED", "objectLabel", "person",
                "confidence", 0.9, "videoOffsetStartMs", 0, "thumbnailKey", "../../etc/passwd")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void zoneFromAnotherCameraIsNotLinked() throws Exception {
        long foreignZone = createZone(otherCamera.getId());
        long jobId = uploadJob();
        var e = new java.util.HashMap<>(event("ZONE_ENTRY", "person", 0.9, 0, 1000));
        e.put("zoneId", foreignZone);
        internal(jobId, "events", events(e)).andExpect(status().isNoContent());
        assertThat(events.findByJobIdOrderByStartedAt(jobId).get(0).getZoneId()).isNull();
    }

    @Test
    void failureIsRecordedAndFinishedJobsAreClosed() throws Exception {
        long jobId = uploadJob();
        internal(jobId, "status", "{\"status\":\"FAILED\",\"error\":\"video not found in storage\"}")
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/jobs/" + jobId).header("Authorization", bearer(operator)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value("video not found in storage"));
        internal(jobId, "status", "{\"status\":\"RUNNING\"}").andExpect(status().isConflict());
        internal(jobId, "events", events()).andExpect(status().isConflict());
    }

    @Test
    void unknownJobIsNotFound() throws Exception {
        internal(999_999, "status", "{\"status\":\"RUNNING\"}").andExpect(status().isNotFound());
    }

    // --- Search and access ---

    @Test
    void operatorSearchesOnlyAssignedCameras() throws Exception {
        long mine = uploadJob();
        long theirs = uploadJob(admin, otherCamera.getId());
        internal(mine, "events", events(event("OBJECT_DETECTED", "person", 0.9, 0, 1000))).andExpect(status().isNoContent());
        internal(theirs, "events", events(event("OBJECT_DETECTED", "person", 0.9, 0, 1000))).andExpect(status().isNoContent());

        mvc.perform(get("/api/events").header("Authorization", bearer(operator)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].cameraId").value(camera.getId()));
        mvc.perform(get("/api/events").param("cameraId", String.valueOf(otherCamera.getId()))
                        .header("Authorization", bearer(operator)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/events").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.totalElements").value(2));
        assertThat(lastAuditAction()).isEqualTo("EVENT_SEARCH");

        long theirEvent = events.findByJobIdOrderByStartedAt(theirs).get(0).getId();
        mvc.perform(get("/api/events/" + theirEvent).header("Authorization", bearer(operator)))
                .andExpect(status().isNotFound());
        long theirVideo = jobs.findById(theirs).orElseThrow().getVideoId();
        mvc.perform(get("/api/videos/" + theirVideo).header("Authorization", bearer(operator)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/" + theirs).header("Authorization", bearer(operator)))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchFiltersByTypeLabelAndConfidence() throws Exception {
        long jobId = uploadJob();
        internal(jobId, "events", events(event("OBJECT_DETECTED", "person", 0.9, 0, 1000),
                event("OBJECT_DETECTED", "car", 0.5, 0, 1000), event("LOITERING", "person", 0.6, 0, 1000)))
                .andExpect(status().isNoContent());
        String auth = bearer(operator);
        mvc.perform(get("/api/events").param("eventType", "LOITERING").header("Authorization", auth))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/events").param("objectLabel", "car").header("Authorization", auth))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/events").param("minConfidence", "0.55").param("sort", "confidence,desc")
                        .header("Authorization", auth))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].confidence").value(0.9));
        mvc.perform(get("/api/events").param("sort", "id;drop table").header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }

    // --- Deletion ---

    @Test
    void adminDeletesVideoWithJobsEventsAndFile() throws Exception {
        long jobId = uploadJob();
        internal(jobId, "events", events(event("OBJECT_DETECTED", "person", 0.9, 0, 1000))).andExpect(status().isNoContent());
        var video = videos.findById(jobs.findById(jobId).orElseThrow().getVideoId()).orElseThrow();
        Path file = storageDir.resolve(video.getStorageKey());
        assertThat(file).exists();

        mvc.perform(delete("/api/videos/" + video.getId()).header("Authorization", bearer(operator)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/videos/" + video.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        assertThat(videos.findById(video.getId())).isEmpty();
        assertThat(jobs.findById(jobId)).isEmpty();
        assertThat(events.findByJobIdOrderByStartedAt(jobId)).isEmpty();
        assertThat(file).doesNotExist();
        assertThat(lastAuditAction()).isEqualTo("VIDEO_DELETE");
    }

    // --- Zones and rules ---

    @Test
    void zoneAndRuleValidation() throws Exception {
        String auth = bearer(admin);
        long cam = camera.getId();
        postJson("/api/cameras/" + cam + "/zones", auth, Map.of("name", "Bad", "polygon", List.of(List.of(0.1, 0.1), List.of(0.5, 0.5))))
                .andExpect(status().isBadRequest());
        postJson("/api/cameras/" + cam + "/zones", auth, Map.of("name", "Bad", "polygon",
                List.of(List.of(0.1, 0.1), List.of(1.5, 0.5), List.of(0.2, 0.9))))
                .andExpect(status().isBadRequest());
        postJson("/api/cameras/" + cam + "/rules", auth, Map.of("type", "LOITERING", "params", Map.of("minSeconds", 30)))
                .andExpect(status().isBadRequest()); // no zone
        postJson("/api/cameras/" + cam + "/rules", auth, Map.of("type", "AFTER_HOURS_VEHICLE",
                "params", Map.of("start", "25:00", "end", "06:00"))).andExpect(status().isBadRequest());
        postJson("/api/cameras/" + cam + "/rules", auth, Map.of("type", "AFTER_HOURS_VEHICLE",
                "params", Map.of("start", "22:00", "end", "06:00", "timezone", "Mars/Base"))).andExpect(status().isBadRequest());
        long foreign = createZone(otherCamera.getId());
        postJson("/api/cameras/" + cam + "/rules", auth, Map.of("type", "ZONE_ENTRY", "zoneId", foreign, "params", Map.of()))
                .andExpect(status().isBadRequest());
        postJson("/api/cameras/" + cam + "/rules", auth, Map.of("type", "AFTER_HOURS_VEHICLE",
                "params", Map.of("start", "22:00", "end", "06:00", "timezone", "America/Toronto")))
                .andExpect(status().isCreated());
    }

    @Test
    void operatorReadsButCannotChangeZones() throws Exception {
        long zoneId = createZone(camera.getId());
        mvc.perform(get("/api/cameras/" + camera.getId() + "/zones").header("Authorization", bearer(operator)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(zoneId));
        postJson("/api/cameras/" + camera.getId() + "/zones", bearer(operator), Map.of("name", "Z", "polygon",
                List.of(List.of(0.1, 0.1), List.of(0.5, 0.1), List.of(0.5, 0.5)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/cameras/" + otherCamera.getId() + "/zones").header("Authorization", bearer(operator)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingZoneRemovesItsRules() throws Exception {
        long zoneId = createZone(camera.getId());
        createRule(camera.getId(), Map.of("type", "ZONE_ENTRY", "zoneId", zoneId, "params", Map.of()));
        mvc.perform(delete("/api/zones/" + zoneId).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/cameras/" + camera.getId() + "/rules").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- Helpers ---

    private User createUser(Role role) {
        return users.saveAndFlush(new User(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@test.local",
                passwordEncoder.encode(PASSWORD), "Test " + role, role));
    }

    private Camera createCamera(String name) {
        Camera c = new Camera();
        c.setName(name);
        return cameras.saveAndFlush(c);
    }

    private long createZone(long cameraId) throws Exception {
        return read(postJson("/api/cameras/" + cameraId + "/zones", bearer(admin), Map.of("name", "Zone",
                "polygon", List.of(List.of(0.0, 0.5), List.of(0.5, 0.5), List.of(0.5, 1.0), List.of(0.0, 1.0))))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private void createRule(long cameraId, Map<String, Object> rule) throws Exception {
        postJson("/api/cameras/" + cameraId + "/rules", bearer(admin), rule).andExpect(status().isCreated());
    }

    private ResultActions upload(User user, long cameraId, String filename, byte[] content, String recordedAt)
            throws Exception {
        return mvc.perform(multipart("/api/cameras/" + cameraId + "/videos")
                .file(new MockMultipartFile("file", filename, "video/mp4", content))
                .param("recordedStartAt", recordedAt)
                .header("Authorization", bearer(user)));
    }

    private long uploadJob() throws Exception {
        return uploadJob(operator, camera.getId());
    }

    private long uploadJob(User user, long cameraId) throws Exception {
        return read(upload(user, cameraId, "a.mp4", MP4, "2026-10-04T21:30:00Z").andExpect(status().isAccepted()))
                .at("/job/id").asLong();
    }

    private ResultActions internal(long jobId, String path, String body) throws Exception {
        return mvc.perform(post("/internal/jobs/" + jobId + "/" + path).header("X-Service-Token", SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static Map<String, Object> event(String type, String label, double confidence, int startMs, int endMs) {
        return Map.of("eventType", type, "objectLabel", label, "trackId", 1, "confidence", confidence,
                "videoOffsetStartMs", startMs, "videoOffsetEndMs", endMs,
                "bbox", Map.of("x", 0.1, "y", 0.2, "width", 0.1, "height", 0.3));
    }

    @SafeVarargs
    private String events(Map<String, Object>... list) throws Exception {
        return json.writeValueAsString(Map.of("events", List.of(list)));
    }

    private ResultActions postJson(String path, String auth, Object body) throws Exception {
        return mvc.perform(post(path).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String bearer(User user) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asText();
    }

    private String lastAuditAction() {
        return auditLogs.findAll().stream().reduce((a, b) -> b).orElseThrow().getAction();
    }

    private static long countStoredFiles() throws IOException {
        try (Stream<Path> files = Files.walk(storageDir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
