package com.cctv.platform.job;

import com.cctv.platform.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    public record JobResponse(Long id, Long videoId, ProcessingJob.Status status, Integer eventCount, String error,
                              Instant createdAt, Instant startedAt, Instant finishedAt) {
        public static JobResponse of(ProcessingJob j) {
            return new JobResponse(j.getId(), j.getVideoId(), j.getStatus(),
                    j.getStatus() == ProcessingJob.Status.DONE ? j.getEventCount() : null, j.getErrorMessage(),
                    j.getCreatedAt(), j.getStartedAt(), j.getFinishedAt());
        }
    }

    @GetMapping("/{jobId}")
    public JobResponse get(@PathVariable long jobId, CurrentUser user) {
        return JobResponse.of(jobs.getVisible(jobId, user));
    }
}
