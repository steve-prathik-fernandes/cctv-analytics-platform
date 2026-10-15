package com.cctv.platform.job;

import com.cctv.platform.job.JobService.EventSubmission;
import com.cctv.platform.job.JobService.StatusUpdate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Callbacks from the detection service. Authenticated by the service token in
 * {@link com.cctv.platform.auth.ServiceTokenFilter}; never reachable with a user token.
 */
@RestController
@RequestMapping("/internal/jobs/{jobId}")
public class InternalJobController {

    private final JobService jobs;

    public InternalJobController(JobService jobs) {
        this.jobs = jobs;
    }

    record EventBatch(List<EventSubmission> events) {
    }

    @PostMapping("/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void status(@PathVariable long jobId, @RequestBody StatusUpdate update) {
        jobs.updateStatus(jobId, update);
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void events(@PathVariable long jobId, @RequestBody EventBatch batch) {
        jobs.storeEvents(jobId, batch.events());
    }
}
