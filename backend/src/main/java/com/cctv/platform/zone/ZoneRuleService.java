package com.cctv.platform.zone;

import com.cctv.platform.audit.AuditAction;
import com.cctv.platform.audit.AuditService;
import com.cctv.platform.auth.CurrentUser;
import com.cctv.platform.camera.CameraService;
import com.cctv.platform.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zones and event rules (UC-03). Validation matches what the detection service accepts
 * (ml-service/app/models.py), so a saved rule never makes a job fail.
 */
@Service
public class ZoneRuleService {

    static final int MAX_POLYGON_POINTS = 50;

    private final ZoneRepository zones;
    private final EventRuleRepository rules;
    private final CameraService cameras;
    private final AuditService audit;

    public ZoneRuleService(ZoneRepository zones, EventRuleRepository rules, CameraService cameras,
                           AuditService audit) {
        this.zones = zones;
        this.rules = rules;
        this.cameras = cameras;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Zone> listZones(long cameraId, CurrentUser user) {
        cameras.getVisible(cameraId, user);
        return zones.findByCameraIdOrderById(cameraId);
    }

    @Transactional
    public Zone createZone(long cameraId, String name, List<List<Double>> polygon, CurrentUser actor) {
        cameras.getVisible(cameraId, actor);
        validatePolygon(polygon);
        Zone zone = zones.save(new Zone(cameraId, name.trim(), polygon));
        audit.record(actor.id(), AuditAction.ZONE_CREATE, "zone", zone.getId(),
                Map.of("cameraId", cameraId, "name", zone.getName()));
        return zone;
    }

    @Transactional
    public void deleteZone(long zoneId, CurrentUser actor) {
        Zone zone = zones.findById(zoneId).orElseThrow(() -> ApiException.notFound("Zone not found."));
        audit.record(actor.id(), AuditAction.ZONE_DELETE, "zone", zoneId, Map.of("cameraId", zone.getCameraId()));
        zones.delete(zone); // zone-scoped rules go with it; events keep their history
    }

    @Transactional(readOnly = true)
    public List<EventRule> listRules(long cameraId, CurrentUser user) {
        cameras.getVisible(cameraId, user);
        return rules.findByCameraIdOrderById(cameraId);
    }

    @Transactional
    public EventRule createRule(long cameraId, RuleInput input, CurrentUser actor) {
        cameras.getVisible(cameraId, actor);
        EventRule rule = apply(new EventRule(cameraId), input);
        rule = rules.save(rule);
        audit.record(actor.id(), AuditAction.RULE_CREATE, "rule", rule.getId(), describe(rule));
        return rule;
    }

    @Transactional
    public EventRule updateRule(long ruleId, RuleInput input, CurrentUser actor) {
        EventRule rule = apply(findRule(ruleId), input);
        audit.record(actor.id(), AuditAction.RULE_UPDATE, "rule", rule.getId(), describe(rule));
        return rule;
    }

    @Transactional
    public void deleteRule(long ruleId, CurrentUser actor) {
        EventRule rule = findRule(ruleId);
        audit.record(actor.id(), AuditAction.RULE_DELETE, "rule", ruleId, describe(rule));
        rules.delete(rule);
    }

    public record RuleInput(Long zoneId, EventRule.Type type, Map<String, Object> params, Boolean enabled) {
    }

    private EventRule findRule(long ruleId) {
        return rules.findById(ruleId).orElseThrow(() -> ApiException.notFound("Rule not found."));
    }

    private EventRule apply(EventRule rule, RuleInput input) {
        if (input.type() == null) throw badRequest("type is required.");
        Map<String, Object> params = input.params() == null ? new HashMap<>() : new HashMap<>(input.params());

        if (input.zoneId() != null) {
            Zone zone = zones.findById(input.zoneId())
                    .filter(z -> z.getCameraId().equals(rule.getCameraId()))
                    .orElseThrow(() -> badRequest("zoneId must be a zone of this camera."));
            rule.setZoneId(zone.getId());
        } else {
            rule.setZoneId(null);
        }

        switch (input.type()) {
            case LOITERING -> {
                requireZone(input);
                if (!(params.get("minSeconds") instanceof Number n) || n.doubleValue() <= 0) {
                    throw badRequest("LOITERING needs params.minSeconds greater than 0.");
                }
            }
            case ZONE_ENTRY -> requireZone(input);
            case AFTER_HOURS_VEHICLE -> {
                parseTime(params.get("start"), "start");
                parseTime(params.get("end"), "end");
                Object tz = params.get("timezone");
                if (tz != null) {
                    try {
                        ZoneId.of(String.valueOf(tz));
                    } catch (DateTimeException e) {
                        throw badRequest("params.timezone must be an IANA time zone such as America/Toronto.");
                    }
                }
            }
        }
        rule.setRuleType(input.type());
        rule.setParams(params);
        if (input.enabled() != null) rule.setEnabled(input.enabled());
        return rule;
    }

    private static void requireZone(RuleInput input) {
        if (input.zoneId() == null) throw badRequest(input.type() + " needs a zoneId.");
    }

    private static void parseTime(Object value, String field) {
        try {
            LocalTime.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            throw badRequest("params." + field + " must be a time as HH:MM.");
        }
    }

    static void validatePolygon(List<List<Double>> polygon) {
        if (polygon == null || polygon.size() < 3 || polygon.size() > MAX_POLYGON_POINTS) {
            throw badRequest("polygon needs 3 to " + MAX_POLYGON_POINTS + " points.");
        }
        for (List<Double> point : polygon) {
            if (point == null || point.size() != 2 || point.stream().anyMatch(v -> v == null || v < 0 || v > 1)) {
                throw badRequest("Each polygon point must be [x, y] with values from 0 to 1.");
            }
        }
    }

    private static Map<String, Object> describe(EventRule rule) {
        Map<String, Object> details = new HashMap<>();
        details.put("cameraId", rule.getCameraId());
        details.put("type", rule.getRuleType().name());
        details.put("enabled", rule.isEnabled());
        if (rule.getZoneId() != null) details.put("zoneId", rule.getZoneId());
        return details;
    }

    private static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }
}
