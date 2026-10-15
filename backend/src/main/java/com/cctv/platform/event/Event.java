package com.cctv.platform.event;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** One tracked object or triggered rule, as reported by the detection service. */
@Entity
@Table(name = "events")
public class Event {

    public enum Type { OBJECT_DETECTED, LOITERING, ZONE_ENTRY, AFTER_HOURS_VEHICLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long cameraId;

    @Column(nullable = false, updatable = false)
    private Long videoId;

    @Column(nullable = false, updatable = false)
    private Long jobId;

    private Long zoneId;

    private Long ruleId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private Type eventType;

    @Column(nullable = false, length = 20)
    private String objectLabel;

    private Integer trackId;

    private Double confidence;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    @Column(nullable = false)
    private int videoOffsetStartMs;

    private Integer videoOffsetEndMs;

    @JdbcTypeCode(SqlTypes.JSON)
    private BoundingBox bbox;

    @Column(length = 512)
    private String thumbnailKey;

    @Column(length = 512)
    private String clipKey;

    protected Event() {
    }

    public Event(Long cameraId, Long videoId, Long jobId, Type eventType, String objectLabel, Integer trackId,
                 Double confidence, Instant startedAt, Instant endedAt, int videoOffsetStartMs,
                 Integer videoOffsetEndMs, BoundingBox bbox, Long zoneId, Long ruleId, String thumbnailKey,
                 String clipKey) {
        this.cameraId = cameraId;
        this.videoId = videoId;
        this.jobId = jobId;
        this.eventType = eventType;
        this.objectLabel = objectLabel;
        this.trackId = trackId;
        this.confidence = confidence;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.videoOffsetStartMs = videoOffsetStartMs;
        this.videoOffsetEndMs = videoOffsetEndMs;
        this.bbox = bbox;
        this.zoneId = zoneId;
        this.ruleId = ruleId;
        this.thumbnailKey = thumbnailKey;
        this.clipKey = clipKey;
    }

    public Long getId() { return id; }
    public Long getCameraId() { return cameraId; }
    public Long getVideoId() { return videoId; }
    public Long getJobId() { return jobId; }
    public Long getZoneId() { return zoneId; }
    public Long getRuleId() { return ruleId; }
    public Type getEventType() { return eventType; }
    public String getObjectLabel() { return objectLabel; }
    public Integer getTrackId() { return trackId; }
    public Double getConfidence() { return confidence; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public int getVideoOffsetStartMs() { return videoOffsetStartMs; }
    public Integer getVideoOffsetEndMs() { return videoOffsetEndMs; }
    public BoundingBox getBbox() { return bbox; }
    public String getThumbnailKey() { return thumbnailKey; }
    public String getClipKey() { return clipKey; }
}
