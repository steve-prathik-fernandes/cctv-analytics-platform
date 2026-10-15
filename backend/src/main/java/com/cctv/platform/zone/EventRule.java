package com.cctv.platform.zone;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "event_rules")
public class EventRule {

    public enum Type { LOITERING, ZONE_ENTRY, AFTER_HOURS_VEHICLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cameraId;

    private Long zoneId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private Type ruleType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> params;

    @Column(nullable = false)
    private boolean enabled = true;

    protected EventRule() {
    }

    public EventRule(Long cameraId) {
        this.cameraId = cameraId;
    }

    public Long getId() { return id; }
    public Long getCameraId() { return cameraId; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long zoneId) { this.zoneId = zoneId; }
    public Type getRuleType() { return ruleType; }
    public void setRuleType(Type ruleType) { this.ruleType = ruleType; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
