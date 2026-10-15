package com.cctv.platform.video;

import com.cctv.platform.auth.CurrentUser;
import com.cctv.platform.job.JobController.JobResponse;
import com.cctv.platform.job.JobService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

@RestController
@RequestMapping("/api")
public class VideoController {

    private final VideoService videos;
    private final JobService jobs;

    public VideoController(VideoService videos, JobService jobs) {
        this.videos = videos;
        this.jobs = jobs;
    }

    public record VideoResponse(Long id, Long cameraId, String originalFilename, long sizeBytes,
                                Double durationSeconds, Instant recordedStartAt, Instant uploadedAt, Long uploadedBy) {
        static VideoResponse of(Video v) {
            return new VideoResponse(v.getId(), v.getCameraId(), v.getOriginalFilename(), v.getSizeBytes(),
                    v.getDurationSeconds(), v.getRecordedStartAt(), v.getUploadedAt(), v.getUploadedBy());
        }
    }

    record UploadResult(VideoResponse video, JobResponse job) {
    }

    @PostMapping("/cameras/{cameraId}/videos")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UploadResult upload(@PathVariable long cameraId, @RequestParam("file") MultipartFile file,
                               @RequestParam("recordedStartAt")
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedStartAt,
                               CurrentUser user) {
        VideoService.Upload upload = videos.upload(cameraId, file, recordedStartAt, user);
        // Re-read the job: dispatch may already have marked it FAILED.
        return new UploadResult(VideoResponse.of(upload.video()),
                JobResponse.of(jobs.getVisible(upload.job().getId(), user)));
    }

    @GetMapping("/videos/{videoId}")
    public VideoResponse get(@PathVariable long videoId, CurrentUser user) {
        return VideoResponse.of(videos.getVisible(videoId, user));
    }

    @DeleteMapping("/videos/{videoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable long videoId, CurrentUser user) {
        videos.delete(videoId, user);
    }
}
