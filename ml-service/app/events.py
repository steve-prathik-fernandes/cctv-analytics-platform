"""Turns tracked detections into events and applies the event rules.

This module is pure Python (no model, no video decoding) so it can be unit tested in CI.
See section 5 of docs/03-architecture.md.
"""
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

from .models import BoundingBox, EventSubmission, JobRequest, Rule, Zone, parse_window

VEHICLE_LABELS = frozenset({"car", "motorcycle", "bus", "truck"})

# Tracks seen in fewer sampled frames than this are treated as detector flicker and dropped.
MIN_TRACK_OBSERVATIONS = 3


@dataclass(frozen=True)
class Observation:
    """One tracked object in one sampled frame. Box coordinates are normalized 0..1."""

    track_id: int
    label: str
    confidence: float
    offset_ms: int
    x1: float
    y1: float
    x2: float
    y2: float

    @property
    def anchor(self) -> tuple[float, float]:
        # Bottom-center of the box: where a person stands or a vehicle meets the road.
        # Zones are drawn on the ground, so this is the point that should be inside them.
        return ((self.x1 + self.x2) / 2, self.y2)

    def bbox(self) -> BoundingBox:
        return BoundingBox(x=self.x1, y=self.y1, width=self.x2 - self.x1, height=self.y2 - self.y1)


def point_in_polygon(point: tuple[float, float], polygon: list[tuple[float, float]]) -> bool:
    """Ray casting test. Points exactly on an edge may fall either way, which is fine here."""
    x, y = point
    inside = False
    n = len(polygon)
    for i in range(n):
        x1, y1 = polygon[i]
        x2, y2 = polygon[(i + 1) % n]
        if (y1 > y) != (y2 > y):
            x_cross = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
            if x < x_cross:
                inside = not inside
    return inside


def build_events(observations: list[Observation], job: JobRequest) -> list[EventSubmission]:
    """One OBJECT_DETECTED event per track, plus any rule events, ordered by start time."""
    tracks: dict[int, list[Observation]] = defaultdict(list)
    for obs in observations:
        tracks[obs.track_id].append(obs)

    zones = {z.id: z for z in job.zones}
    events: list[EventSubmission] = []
    for track_id, track in tracks.items():
        if len(track) < MIN_TRACK_OBSERVATIONS:
            continue
        track.sort(key=lambda o: o.offset_ms)
        # The detector can flip between similar classes (car/truck); use the majority label.
        label = Counter(o.label for o in track).most_common(1)[0][0]

        events.append(_event("OBJECT_DETECTED", label, track_id, track))
        for rule in job.rules:
            zone = zones.get(rule.zoneId) if rule.zoneId is not None else None
            events.extend(_apply_rule(rule, zone, label, track_id, track, job.recordedStartAt))

    events.sort(key=lambda e: (e.videoOffsetStartMs, e.trackId, e.eventType))
    return events


def _apply_rule(rule: Rule, zone: Zone | None, label: str, track_id: int, track: list[Observation],
                recorded_start: datetime) -> list[EventSubmission]:
    if rule.type == "ZONE_ENTRY":
        runs = _inside_runs(track, zone)
        if not runs:
            return []
        # Only the first entry per track, as the architecture describes.
        return [_event("ZONE_ENTRY", label, track_id, runs[0], zone_id=zone.id, rule_id=rule.id)]

    if rule.type == "LOITERING":
        if label != "person":
            return []
        min_ms = float(rule.params["minSeconds"]) * 1000
        return [
            _event("LOITERING", label, track_id, run, zone_id=zone.id, rule_id=rule.id)
            for run in _inside_runs(track, zone)
            if run[-1].offset_ms - run[0].offset_ms >= min_ms
        ]

    if rule.type == "AFTER_HOURS_VEHICLE":
        if label not in VEHICLE_LABELS:
            return []
        start, end, tz = parse_window(rule)
        zone_obs = track if zone is None else [o for o in track if point_in_polygon(o.anchor, zone.polygon)]
        hits = [o for o in zone_obs if _in_window(recorded_start + timedelta(milliseconds=o.offset_ms),
                                                  start, end, ZoneInfo(tz))]
        if not hits:
            return []
        return [_event("AFTER_HOURS_VEHICLE", label, track_id, hits,
                       zone_id=zone.id if zone else None, rule_id=rule.id)]

    return []


def _inside_runs(track: list[Observation], zone: Zone) -> list[list[Observation]]:
    """Consecutive stretches of a track whose anchor point is inside the zone."""
    runs: list[list[Observation]] = []
    current: list[Observation] = []
    for obs in track:
        if point_in_polygon(obs.anchor, zone.polygon):
            current.append(obs)
        elif current:
            runs.append(current)
            current = []
    if current:
        runs.append(current)
    return runs


def _in_window(moment: datetime, start: time, end: time, tz: ZoneInfo) -> bool:
    local = moment.astimezone(tz).time()
    if start <= end:
        return start <= local < end
    return local >= start or local < end  # window crosses midnight, e.g. 22:00 to 06:00


def _event(event_type: str, label: str, track_id: int, span: list[Observation],
           zone_id: int | None = None, rule_id: int | None = None) -> EventSubmission:
    best = max(span, key=lambda o: o.confidence)
    return EventSubmission(
        eventType=event_type,
        objectLabel=label,
        trackId=track_id,
        confidence=round(best.confidence, 3),
        videoOffsetStartMs=span[0].offset_ms,
        videoOffsetEndMs=span[-1].offset_ms,
        bbox=best.bbox(),
        zoneId=zone_id,
        ruleId=rule_id,
    )
