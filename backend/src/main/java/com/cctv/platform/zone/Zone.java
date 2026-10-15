package com.cctv.platform.zone;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/** A restricted area on a camera's view. Points are [x, y] normalized to 0..1. */
@Entity
@Table(name = "zones")
public class Zone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cameraId;

    @Column(nullable = false, length = 120)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<List<Double>> polygon;

    protected Zone() {
    }

    public Zone(Long cameraId, String name, List<List<Double>> polygon) {
        this.cameraId = cameraId;
        this.name = name;
        this.polygon = polygon;
    }

    public Long getId() { return id; }
    public Long getCameraId() { return cameraId; }
    public String getName() { return name; }
    public List<List<Double>> getPolygon() { return polygon; }
}
