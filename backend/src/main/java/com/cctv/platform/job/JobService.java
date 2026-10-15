package com.cctv.platform.job;

import com.cctv.platform.auth.CurrentUser;
import com.cctv.platform.camera.CameraService;
import com.cctv.platform.common.ApiException;
import com.cctv.platform.event.BoundingBox;
import com.cctv.platform.event.Event;
import com.cctv.platform.event.EventRepository;
import com.cctv.platform.job.DetectionClient.JobPayload;
import com.cctv.platform.job.DetectionClient.RulePayload;
import com.cctv.platform.job.DetectionClient.ZonePayload;
import com.cctv.platform.video.Video;
import com.cctv.platform.video.VideoRepository;
import com.cctv.platform.zone.EventRule;
import com.cctv.platform.zone.EventRuleRepository;
import com.cctv.platform.zone.Zone;
import com.cctv.platform.zone.ZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Job lifecycle: dispatch to the detection service, then status and results from its callbacks. */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final Set<String> LABELS = Set.of("person", "car", "motorcycle", "bus", "truck");
    /** Upper bound per callback, so one bad request cannot exhaust memory or the database. */
    static final int MAX_EVENTS_PER_JOB = 10_000;
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_\\-][A-Za-z0-9_./\\-]*");

    private final ProcessingJobRepository jobs;
    private final VideoRepository videos;
    private final EventRepository events;
    private final ZoneRepository zones;
    private final EventRuleRepository rules;
    private final CameraService cameras;
    private final DetectionClient detection;
    private final DetectionProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JobService(ProcessingJobRepository jobs, VideoRepository videos, EventRepository events,
                      ZoneRepository zones, EventRuleRepository rules, CameraService cameras,
                      DetectionClient detection, DetectionProperties props, TransactionTemplate tx, Clock clock) {
        this.jobs = jobs;
        this.videos = videos;
        this.events = events;
        this.zones = zones;
        this.rules = rules;
        this.cameras = cameras;
        this.detection = detection;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    public ProcessingJob newJob(Video video) {
        return jobs.save(new ProcessingJob(video.getId(), props.modelName(), props.frameStride()));
    }

    /**
     * Sends a committed job to the detection service. Runs outside a transaction so the HTTP
     * call does not hold a database connection. If the service cannot be reached the job is
     * marked FAILED rather than left QUEUED forever.
     */
    public void dispatch(long jobId) {
        JobPayload payload = tx.execute(status -> buildPayload(jobId));
        try {
            detection.submit(payload);
        } catch (RuntimeException e) {
            log.warn("Could not submit job {} to the detection service: {}", jobId, e.toString());
            tx.executeWithoutResult(status -> jobs.findById(jobId).ifPresent(job ->
                    job.markFailed("The detection service could not be reached. Try uploading again later.",
                            clock.instant())));
        }
    }

    private JobPayload buildPayload(long jobId) {
        ProcessingJob job = jobs.findById(jobId).orElseThrow();
        Video video = videos.findById(job.getVideoId()).orElseThrow();
        long cameraId = video.getCameraId();
        List<ZonePayload> zonePayloads = zones.findByCameraIdOrderById(cameraId).stream()
                .map(z -> new ZonePayload(z.getId(), z.getName(), z.getPolygon())).toList();
        List<RulePayload> rulePayloads = rules.findByCameraIdAndEnabledTrueOrderById(cameraId).stream()
                .map(r -> new RulePayload(r.getId(), r.getRuleType().name(), r.getZoneId(), r.getParams())).toList();
        return new JobPayload(job.getId(), cameraId, video.getStorageKey(), video.getRecordedStartAt(),
                job.getFrameStride(), props.minConfidence(), zonePayloads, rulePayloads);
    }

    public List<ProcessingJob> forVideo(long videoId) {
        return jobs.findByVideoIdOrderById(videoId);
    }

    public void deleteAll(List<ProcessingJob> list) {
        jobs.deleteAllInBatch(list);
    }

    @Transactional(readOnly = true)
    public ProcessingJob getVisible(long jobId, CurrentUser user) {
        ProcessingJob job = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
        Video video = videos.findById(job.getVideoId()).orElseThrow(() -> ApiException.notFound("Job not found."));
        try {
            cameras.getVisible(video.getCameraId(), user);
        } catch (ApiException e) {
            throw ApiException.notFound("Job not found.");
        }
        return job;
    }

    // --- Callbacks from the detection service ---

    public record StatusUpdate(String status, String error) {
    }

    public record EventSubmission(Event.Type eventType, String objectLabel, Integer trackId, Double confidence,
                                  Integer videoOffsetStartMs, Integer videoOffsetEndMs, BoundingBox bbox,
                                  Long zoneId, Long ruleId, String thumbnailKey, String clipKey) {
    }

    @Transactional
    public void updateStatus(long jobId, StatusUpdate update) {
        ProcessingJob job = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
        if (job.getStatus() == ProcessingJob.Status.DONE || job.getStatus() == ProcessingJob.Status.FAILED) {
            throw ApiException.conflict("Job is already finished.");
        }
        switch (update.status() == null ? "" : update.status()) {
            case "RUNNING" -> job.markRunning(clock.instant());
            case "FAILED" -> job.markFailed(update.error(), clock.instant());
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "status must be RUNNING or FAILED.");
        }
    }

    /** Stores the job's events, replacing any sent before, and marks the job DONE. */
    @Transactional
    public int storeEvents(long jobId, List<EventSubmission> submissions) {
        ProcessingJob job = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
        if (job.getStatus() == ProcessingJob.Status.FAILED) {
            throw ApiException.conflict("Job has failed; submit a new job instead.");
        }
        if (submissions == null) throw new ApiException(HttpStatus.BAD_REQUEST, "events is required.");
        if (submissions.size() > MAX_EVENTS_PER_JOB) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Too many events (limit " + MAX_EVENTS_PER_JOB + ").");
        }
        Video video = videos.findById(job.getVideoId()).orElseThrow();
        Set<Long> zoneIds = zones.findByCameraIdOrderById(video.getCameraId()).stream()
                .map(Zone::getId).collect(Collectors.toSet());
        Set<Long> ruleIds = rules.findByCameraIdOrderById(video.getCameraId()).stream()
                .map(EventRule::getId).collect(Collectors.toSet());

        List<Event> rows = submissions.stream().map(s -> toEvent(s, job, video, zoneIds, ruleIds)).toList();
        events.deleteByJobId(jobId);
        events.saveAll(rows);
        // Re-read: deleteByJobId cleared the persistence context.
        jobs.findById(jobId).orElseThrow().markDone(rows.size(), clock.instant());
        return rows.size();
    }

    private static Event toEvent(EventSubmission s, ProcessingJob job, Video video, Set<Long> zoneIds,
                                 Set<Long> ruleIds) {
        if (s == null || s.eventType() == null || s.objectLabel() == null || !LABELS.contains(s.objectLabel())) {
            throw bad("Each event needs a valid eventType and objectLabel.");
        }
        if (s.videoOffsetStartMs() == null || s.videoOffsetStartMs() < 0
                || (s.videoOffsetEndMs() != null && s.videoOffsetEndMs() < s.videoOffsetStartMs())) {
            throw bad("Event offsets must be non-negative and end must not be before start.");
        }
        if (s.confidence() == null || s.confidence() < 0 || s.confidence() > 1) {
            throw bad("Event confidence must be between 0 and 1.");
        }
        if (s.bbox() != null && !s.bbox().isValid()) throw bad("Event bbox must be normalized to 0..1.");
        if (!isSafeKey(s.thumbnailKey()) || !isSafeKey(s.clipKey())) throw bad("Invalid storage key.");
        // A zone or rule from another camera would leak across cameras; drop the link instead.
        Long zoneId = s.zoneId() != null && zoneIds.contains(s.zoneId()) ? s.zoneId() : null;
        Long ruleId = s.ruleId() != null && ruleIds.contains(s.ruleId()) ? s.ruleId() : null;

        var start = video.getRecordedStartAt().plusMillis(s.videoOffsetStartMs());
        var end = s.videoOffsetEndMs() == null ? null : video.getRecordedStartAt().plusMillis(s.videoOffsetEndMs());
        return new Event(video.getCameraId(), video.getId(), job.getId(), s.eventType(), s.objectLabel(),
                s.trackId(), s.confidence(), start, end, s.videoOffsetStartMs(), s.videoOffsetEndMs(), s.bbox(),
                zoneId, ruleId, s.thumbnailKey(), s.clipKey());
    }

    /** Keys become storage paths and signed links later, so only allow plain relative paths. */
    static boolean isSafeKey(String key) {
        return key == null || (key.length() <= 512 && SAFE_KEY.matcher(key).matches() && !key.contains(".."));
    }

    private static ApiException bad(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }
}
