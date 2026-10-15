package com.cctv.platform.video;

import com.cctv.platform.audit.AuditAction;
import com.cctv.platform.audit.AuditService;
import com.cctv.platform.auth.CurrentUser;
import com.cctv.platform.camera.CameraService;
import com.cctv.platform.common.ApiException;
import com.cctv.platform.event.Event;
import com.cctv.platform.event.EventRepository;
import com.cctv.platform.job.JobService;
import com.cctv.platform.job.ProcessingJob;
import com.cctv.platform.storage.VideoStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Footage upload and deletion (UC-04, UC-09). */
@Service
public class VideoService {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private static final int SNIFF_BYTES = 12;
    /** Recorded times slightly ahead of the server clock are allowed for camera clock drift. */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(10);

    private final VideoRepository videos;
    private final EventRepository events;
    private final JobService jobs;
    private final CameraService cameras;
    private final VideoStorage storage;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final long maxBytes;

    public VideoService(VideoRepository videos, EventRepository events, JobService jobs, CameraService cameras,
                        VideoStorage storage, AuditService audit, TransactionTemplate tx, Clock clock,
                        @Value("${app.upload.max-bytes}") long maxBytes) {
        this.videos = videos;
        this.events = events;
        this.jobs = jobs;
        this.cameras = cameras;
        this.storage = storage;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.maxBytes = maxBytes;
    }

    public record Upload(Video video, ProcessingJob job) {
    }

    /**
     * Validates and stores the file, records the video and a QUEUED job, then hands the job to
     * the detection service. Nothing is stored unless every check passes.
     */
    public Upload upload(long cameraId, MultipartFile file, Instant recordedStartAt, CurrentUser user) {
        tx.executeWithoutResult(s -> cameras.getVisible(cameraId, user));

        if (file == null || file.isEmpty()) throw bad("A video file is required.");
        if (file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The file is larger than the " + (maxBytes / (1024 * 1024)) + " MB limit.");
        }
        String displayName = displayName(file.getOriginalFilename());
        if (!displayName.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only MP4 files are accepted.");
        }
        if (recordedStartAt == null) throw bad("recordedStartAt is required.");
        if (recordedStartAt.isAfter(clock.instant().plus(FUTURE_TOLERANCE))) {
            throw bad("recordedStartAt cannot be in the future.");
        }

        String key = newKey(cameraId);
        long size;
        try (InputStream in = new PushbackInputStream(file.getInputStream(), SNIFF_BYTES)) {
            if (!looksLikeMp4((PushbackInputStream) in)) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "The file content is not an MP4 video.");
            }
            size = storage.store(key, in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the upload", e);
        }

        Upload result;
        try {
            result = tx.execute(s -> {
                Video video = videos.save(new Video(cameraId, user.id(), displayName, key, size, recordedStartAt));
                ProcessingJob job = jobs.newJob(video);
                audit.record(user.id(), AuditAction.VIDEO_UPLOAD, "video", video.getId(),
                        Map.of("cameraId", cameraId, "jobId", job.getId(), "sizeBytes", size,
                                "originalFilename", displayName));
                return new Upload(video, job);
            });
        } catch (RuntimeException e) {
            deleteQuietly(key);
            throw e;
        }
        jobs.dispatch(result.job().getId());
        return result;
    }

    public Video getVisible(long videoId, CurrentUser user) {
        return tx.execute(s -> {
            Video video = videos.findById(videoId).orElseThrow(() -> ApiException.notFound("Video not found."));
            try {
                cameras.getVisible(video.getCameraId(), user);
            } catch (ApiException e) {
                throw ApiException.notFound("Video not found.");
            }
            return video;
        });
    }

    /** Deletes the video, its jobs and events (database cascade), then its stored files. */
    public void delete(long videoId, CurrentUser actor) {
        List<String> keys = tx.execute(s -> {
            Video video = videos.findById(videoId).orElseThrow(() -> ApiException.notFound("Video not found."));
            List<String> files = new ArrayList<>();
            files.add(video.getStorageKey());
            List<ProcessingJob> videoJobs = jobs.forVideo(video.getId());
            for (ProcessingJob job : videoJobs) {
                for (Event e : events.findByJobIdOrderByStartedAt(job.getId())) {
                    Stream.of(e.getThumbnailKey(), e.getClipKey()).filter(k -> k != null).forEach(files::add);
                }
            }
            // Audit first, in the same transaction, so a deletion is never unrecorded.
            audit.record(actor.id(), AuditAction.VIDEO_DELETE, "video", videoId,
                    Map.of("cameraId", video.getCameraId(), "originalFilename", video.getOriginalFilename()));
            // Explicit deletes keep JPA's view consistent; the database cascade is the backstop.
            videoJobs.forEach(job -> events.deleteByJobId(job.getId()));
            jobs.deleteAll(videoJobs);
            videos.deleteById(videoId);
            return files;
        });
        keys.forEach(this::deleteQuietly);
    }

    /** MP4 and other ISO base media files have the box type "ftyp" at bytes 4 to 8. */
    static boolean looksLikeMp4(PushbackInputStream in) throws IOException {
        byte[] head = in.readNBytes(SNIFF_BYTES);
        in.unread(head);
        return head.length == SNIFF_BYTES
                && "ftyp".equals(new String(Arrays.copyOfRange(head, 4, 8), StandardCharsets.US_ASCII));
    }

    /** The user's file name, for display only: no directories, no control characters. */
    static String displayName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) name = "video.mp4";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String newKey(long cameraId) {
        ZonedDateTime now = clock.instant().atZone(ZoneOffset.UTC);
        return "videos/%d/%04d/%02d/%s.mp4".formatted(cameraId, now.getYear(), now.getMonthValue(), UUID.randomUUID());
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete stored file {}: {}", key, e.toString());
        }
    }

    private static ApiException bad(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }
}
