package com.cctv.platform.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

@Service
public class AuditService {

    private final AuditLogRepository logs;

    public AuditService(AuditLogRepository logs) {
        this.logs = logs;
    }

    /**
     * Writes an audit entry. Joins the caller's transaction, so the entry is saved only if
     * the audited change is. Never put passwords, tokens or other secrets in {@code details}.
     */
    @Transactional
    public void record(Long userId, AuditAction action, String resourceType, Long resourceId,
                       Map<String, Object> details) {
        logs.save(new AuditLog(userId, action.name(), resourceType, resourceId, clientIp(), details));
    }

    private static String clientIp() {
        // Uses the TCP peer address. Behind the AWS load balancer this needs
        // server.forward-headers-strategy configured to trust only the balancer.
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
