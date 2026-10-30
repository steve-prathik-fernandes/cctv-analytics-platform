package com.cctv.platform.event;

import com.cctv.platform.audit.AuditAction;
import com.cctv.platform.audit.AuditService;
import com.cctv.platform.auth.CurrentUser;
import com.cctv.platform.camera.Camera;
import com.cctv.platform.camera.CameraService;
import com.cctv.platform.common.ApiException;
import com.cctv.platform.common.PageResponse;
import com.cctv.platform.storage.VideoStorage;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Event search (UC-05) and detail (UC-06). Results are limited to cameras the caller may see. */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private static final Map<String, Sort> SORTS = Map.of(
            "startedAt,desc", Sort.by(Sort.Direction.DESC, "startedAt", "id"),
            "startedAt,asc", Sort.by(Sort.Direction.ASC, "startedAt", "id"),
            "confidence,desc", Sort.by(Sort.Direction.DESC, "confidence", "id"));

    private final EventRepository events;
    private final CameraService cameras;
    private final AuditService audit;
    private final VideoStorage storage;
    private final Duration linkTtl;
    private final Clock clock;

    public EventController(EventRepository events, CameraService cameras, AuditService audit, VideoStorage storage,
                           @Value("${app.storage.link-ttl}") Duration linkTtl, Clock clock) {
        this.events = events;
        this.cameras = cameras;
        this.audit = audit;
        this.storage = storage;
        this.linkTtl = linkTtl;
        this.clock = clock;
    }

    public record EventResponse(Long id, Long cameraId, Long videoId, Long jobId, Event.Type eventType,
                                String objectLabel, Integer trackId, Double confidence, Instant startedAt,
                                Instant endedAt, Long zoneId, Long ruleId, BoundingBox bbox, String thumbnailUrl,
                                String clipUrl) {
        public static EventResponse of(Event e) {
            return new EventResponse(e.getId(), e.getCameraId(), e.getVideoId(), e.getJobId(), e.getEventType(),
                    e.getObjectLabel(), e.getTrackId(), e.getConfidence(), e.getStartedAt(), e.getEndedAt(),
                    e.getZoneId(), e.getRuleId(), e.getBbox(),
                    e.getThumbnailKey() == null ? null : "/api/events/" + e.getId() + "/thumbnail",
                    e.getClipKey() == null ? null : "/api/events/" + e.getId() + "/clip");
        }
    }

    @GetMapping
    @Transactional
    public PageResponse<EventResponse> search(
            @RequestParam(required = false) Long cameraId,
            @RequestParam(required = false) Event.Type eventType,
            @RequestParam(required = false) String objectLabel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Double minConfidence,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "startedAt,desc") String sort,
            CurrentUser user) {
        Sort order = SORTS.get(sort);
        if (order == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sort must be one of " + SORTS.keySet());
        }
        if (minConfidence != null && (minConfidence < 0 || minConfidence > 1)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "minConfidence must be between 0 and 1.");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "from must be before to.");
        }

        // Admins see everything; operators only their cameras. A camera filter outside that set
        // simply matches nothing.
        Set<Long> visible = user.isAdmin() ? null
                : cameras.listVisible(user).stream().map(Camera::getId).collect(Collectors.toSet());

        Specification<Event> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (visible != null) where.add(visible.isEmpty() ? cb.disjunction() : root.get("cameraId").in(visible));
            if (cameraId != null) where.add(cb.equal(root.get("cameraId"), cameraId));
            if (eventType != null) where.add(cb.equal(root.get("eventType"), eventType));
            if (objectLabel != null) where.add(cb.equal(root.get("objectLabel"), objectLabel));
            if (from != null) where.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
            if (to != null) where.add(cb.lessThan(root.get("startedAt"), to));
            if (minConfidence != null) where.add(cb.greaterThanOrEqualTo(root.get("confidence"), minConfidence));
            return cb.and(where.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), order);
        PageResponse<EventResponse> result = PageResponse.of(events.findAll(spec, pageable), EventResponse::of);

        Map<String, Object> filters = new HashMap<>();
        if (cameraId != null) filters.put("cameraId", cameraId);
        if (eventType != null) filters.put("eventType", eventType.name());
        if (objectLabel != null) filters.put("objectLabel", objectLabel);
        if (from != null) filters.put("from", from.toString());
        if (to != null) filters.put("to", to.toString());
        if (minConfidence != null) filters.put("minConfidence", minConfidence);
        filters.put("results", result.totalElements());
        audit.record(user.id(), AuditAction.EVENT_SEARCH, "event", null, filters);
        return result;
    }

    @GetMapping("/{eventId}")
    @Transactional
    public EventResponse get(@PathVariable long eventId, CurrentUser user) {
        Event event = visible(eventId, user);
        audit.record(user.id(), AuditAction.EVENT_VIEW, "event", eventId, null);
        return EventResponse.of(event);
    }

    /** A short-lived signed link to the event's clip; see {@link #link}. The view is audited. */
    @GetMapping("/{eventId}/clip")
    @Transactional
    public ResponseEntity<?> clip(@PathVariable long eventId, CurrentUser user,
                                  @RequestParam(defaultValue = "true") boolean redirect) {
        Event event = visible(eventId, user);
        if (event.getClipKey() == null) throw ApiException.notFound("This event has no clip.");
        audit.record(user.id(), AuditAction.CLIP_VIEW, "event", eventId, null);
        return link(event.getClipKey(), redirect);
    }

    /** A short-lived signed link to the event's thumbnail; see {@link #link}. */
    @GetMapping("/{eventId}/thumbnail")
    @Transactional(readOnly = true)
    public ResponseEntity<?> thumbnail(@PathVariable long eventId, CurrentUser user,
                                       @RequestParam(defaultValue = "true") boolean redirect) {
        Event event = visible(eventId, user);
        if (event.getThumbnailKey() == null) throw ApiException.notFound("This event has no thumbnail.");
        return link(event.getThumbnailKey(), redirect);
    }

    public record MediaLink(String url, Instant expiresAt) {
    }

    /**
     * A redirect to the signed link, or with {@code redirect=false} the link as JSON. The
     * dashboard uses JSON: on AWS the link points to S3, another origin, and a script cannot
     * follow a cross-origin redirect without CORS rules on the bucket.
     */
    private ResponseEntity<?> link(String key, boolean redirect) {
        URI url = storage.signedUrl(key, linkTtl);
        if (redirect) return ResponseEntity.status(HttpStatus.FOUND).location(url).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new MediaLink(url.toString(), clock.instant().plus(linkTtl)));
    }

    /** The event if the caller may see its camera; otherwise not found, as for other cameras. */
    private Event visible(long eventId, CurrentUser user) {
        Event event = events.findById(eventId).orElseThrow(() -> ApiException.notFound("Event not found."));
        try {
            cameras.getVisible(event.getCameraId(), user);
        } catch (ApiException e) {
            throw ApiException.notFound("Event not found.");
        }
        return event;
    }
}
