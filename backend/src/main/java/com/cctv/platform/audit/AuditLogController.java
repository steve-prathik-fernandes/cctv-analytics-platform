package com.cctv.platform.audit;

import com.cctv.platform.common.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final AuditLogRepository logs;

    public AuditLogController(AuditLogRepository logs) {
        this.logs = logs;
    }

    record AuditLogResponse(Long id, Long userId, String action, String resourceType, Long resourceId,
                            String ipAddress, Map<String, Object> details, Instant createdAt) {
        static AuditLogResponse of(AuditLog a) {
            return new AuditLogResponse(a.getId(), a.getUserId(), a.getAction(), a.getResourceType(),
                    a.getResourceId(), a.getIpAddress(), a.getDetails(), a.getCreatedAt());
        }
    }

    @GetMapping
    public PageResponse<AuditLogResponse> search(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (userId != null) where.add(cb.equal(root.get("userId"), userId));
            if (action != null) where.add(cb.equal(root.get("action"), action));
            if (from != null) where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) where.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(where.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.of(logs.findAll(spec, pageable), AuditLogResponse::of);
    }
}
