package com.cctv.platform.zone;

import com.cctv.platform.auth.CurrentUser;
import com.cctv.platform.zone.ZoneRuleService.RuleInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ZoneRuleController {

    private final ZoneRuleService service;

    public ZoneRuleController(ZoneRuleService service) {
        this.service = service;
    }

    record ZoneInput(@NotBlank @Size(max = 120) String name, @NotNull List<List<Double>> polygon) {
    }

    record ZoneResponse(Long id, Long cameraId, String name, List<List<Double>> polygon) {
        static ZoneResponse of(Zone z) {
            return new ZoneResponse(z.getId(), z.getCameraId(), z.getName(), z.getPolygon());
        }
    }

    record RuleResponse(Long id, Long cameraId, Long zoneId, EventRule.Type type, Map<String, Object> params,
                        boolean enabled) {
        static RuleResponse of(EventRule r) {
            return new RuleResponse(r.getId(), r.getCameraId(), r.getZoneId(), r.getRuleType(), r.getParams(),
                    r.isEnabled());
        }
    }

    @GetMapping("/cameras/{cameraId}/zones")
    public List<ZoneResponse> listZones(@PathVariable long cameraId, CurrentUser user) {
        return service.listZones(cameraId, user).stream().map(ZoneResponse::of).toList();
    }

    @PostMapping("/cameras/{cameraId}/zones")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ZoneResponse createZone(@PathVariable long cameraId, @Valid @RequestBody ZoneInput input,
                                   CurrentUser user) {
        return ZoneResponse.of(service.createZone(cameraId, input.name(), input.polygon(), user));
    }

    @DeleteMapping("/zones/{zoneId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteZone(@PathVariable long zoneId, CurrentUser user) {
        service.deleteZone(zoneId, user);
    }

    @GetMapping("/cameras/{cameraId}/rules")
    public List<RuleResponse> listRules(@PathVariable long cameraId, CurrentUser user) {
        return service.listRules(cameraId, user).stream().map(RuleResponse::of).toList();
    }

    @PostMapping("/cameras/{cameraId}/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public RuleResponse createRule(@PathVariable long cameraId, @RequestBody RuleInput input, CurrentUser user) {
        return RuleResponse.of(service.createRule(cameraId, input, user));
    }

    @PutMapping("/rules/{ruleId}")
    @PreAuthorize("hasRole('ADMIN')")
    public RuleResponse updateRule(@PathVariable long ruleId, @RequestBody RuleInput input, CurrentUser user) {
        return RuleResponse.of(service.updateRule(ruleId, input, user));
    }

    @DeleteMapping("/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteRule(@PathVariable long ruleId, CurrentUser user) {
        service.deleteRule(ruleId, user);
    }
}
