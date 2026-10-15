package com.cctv.platform.job;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** One processing run of a video by the detection service. */
@Entity
@Table(name = "processing_jobs")
public class ProcessingJob {

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long videoId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Column(nullable = false, length = 100, updatable = false)
    private String modelName;

    @Column(nullable = false, updatable = false)
    private int frameStride;

    @Column(length = 2000)
    private String errorMessage;

    @Column(nullable = false)
    private int eventCount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant startedAt;

    private Instant finishedAt;

    protected ProcessingJob() {
    }

    public ProcessingJob(Long videoId, String modelName, int frameStride) {
        this.videoId = videoId;
        this.modelName = modelName;
        this.frameStride = frameStride;
    }

    public void markRunning(Instant now) {
        status = Status.RUNNING;
        if (startedAt == null) startedAt = now;
    }

    public void markDone(int events, Instant now) {
        status = Status.DONE;
        eventCount = events;
        errorMessage = null;
        finishedAt = now;
    }

    public void markFailed(String error, Instant now) {
        status = Status.FAILED;
        errorMessage = error == null || error.isBlank() ? "Processing failed."
                : error.substring(0, Math.min(error.length(), 2000));
        finishedAt = now;
    }

    public Long getId() { return id; }
    public Long getVideoId() { return videoId; }
    public Status getStatus() { return status; }
    public String getModelName() { return modelName; }
    public int getFrameStride() { return frameStride; }
    public String getErrorMessage() { return errorMessage; }
    public int getEventCount() { return eventCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
