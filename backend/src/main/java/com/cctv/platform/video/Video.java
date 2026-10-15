package com.cctv.platform.video;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "videos")
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long cameraId;

    @Column(nullable = false, updatable = false)
    private Long uploadedBy;

    /** Shown to users only. Never used to build a storage path. */
    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false, unique = true, length = 512, updatable = false)
    private String storageKey;

    @Column(nullable = false)
    private long sizeBytes;

    private Double durationSeconds;

    @Column(nullable = false)
    private Instant recordedStartAt;

    @Column(nullable = false, updatable = false)
    private Instant uploadedAt = Instant.now();

    protected Video() {
    }

    public Video(Long cameraId, Long uploadedBy, String originalFilename, String storageKey, long sizeBytes,
                 Instant recordedStartAt) {
        this.cameraId = cameraId;
        this.uploadedBy = uploadedBy;
        this.originalFilename = originalFilename;
        this.storageKey = storageKey;
        this.sizeBytes = sizeBytes;
        this.recordedStartAt = recordedStartAt;
    }

    public Long getId() { return id; }
    public Long getCameraId() { return cameraId; }
    public Long getUploadedBy() { return uploadedBy; }
    public String getOriginalFilename() { return originalFilename; }
    public String getStorageKey() { return storageKey; }
    public long getSizeBytes() { return sizeBytes; }
    public Double getDurationSeconds() { return durationSeconds; }
    public Instant getRecordedStartAt() { return recordedStartAt; }
    public Instant getUploadedAt() { return uploadedAt; }
}
