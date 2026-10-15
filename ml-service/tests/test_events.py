"""Event building and rules, using hand-made tracks instead of a model."""
from app.events import Observation, build_events, point_in_polygon
from app.models import JobRequest

ZONE = [[0.0, 0.5], [0.5, 0.5], [0.5, 1.0], [0.0, 1.0]]  # bottom-left quarter of the frame


def job(rules=(), zones=None, recorded="2026-10-04T12:00:00Z", **kw):
    zones = [{"id": 1, "name": "Dock", "polygon": ZONE}] if zones is None else zones
    return JobRequest(jobId=1, cameraId=1, videoKey="v.mp4", recordedStartAt=recorded,
                      zones=zones, rules=list(rules), **kw)


def walk(track_id, label, points, step_ms=1000, conf=0.8):
    """A track whose box bottom-center moves through the given points, one per step."""
    return [Observation(track_id, label, conf, i * step_ms, x - 0.05, y - 0.2, x + 0.05, y)
            for i, (x, y) in enumerate(points)]


def types(events):
    return [e.eventType for e in events]


def test_point_in_polygon():
    assert point_in_polygon((0.25, 0.75), ZONE)
    assert not point_in_polygon((0.75, 0.75), ZONE)
    assert not point_in_polygon((0.25, 0.25), ZONE)


def test_one_object_event_per_track_with_best_frame():
    obs = walk(7, "person", [(0.8, 0.3)] * 4)
    obs[2] = Observation(7, "person", 0.95, obs[2].offset_ms, 0.1, 0.1, 0.3, 0.5)
    [event] = build_events(obs, job())

    assert event.eventType == "OBJECT_DETECTED"
    assert event.trackId == 7
    assert (event.videoOffsetStartMs, event.videoOffsetEndMs) == (0, 3000)
    assert event.confidence == 0.95
    assert event.bbox.x == 0.1 and round(event.bbox.width, 6) == 0.2


def test_short_tracks_are_dropped_as_flicker():
    assert build_events(walk(1, "person", [(0.8, 0.3)] * 2), job()) == []


def test_majority_label_wins():
    obs = walk(3, "car", [(0.8, 0.3)] * 5)
    obs[0] = Observation(3, "truck", 0.5, 0, *[0.75, 0.1, 0.85, 0.3])
    [event] = build_events(obs, job())
    assert event.objectLabel == "car"


def test_zone_entry_fires_once_at_first_entry():
    rule = {"id": 5, "type": "ZONE_ENTRY", "zoneId": 1}
    # outside, inside, inside, outside, inside again
    obs = walk(2, "person", [(0.8, 0.8), (0.2, 0.8), (0.3, 0.8), (0.8, 0.8), (0.2, 0.8)])
    events = build_events(obs, job([rule]))

    entries = [e for e in events if e.eventType == "ZONE_ENTRY"]
    assert len(entries) == 1
    assert (entries[0].videoOffsetStartMs, entries[0].videoOffsetEndMs) == (1000, 2000)
    assert (entries[0].zoneId, entries[0].ruleId) == (1, 5)


def test_zone_uses_bottom_center_of_box():
    # Box top is inside the zone's y range but its feet are above the zone: not inside.
    rule = {"id": 5, "type": "ZONE_ENTRY", "zoneId": 1}
    obs = [Observation(1, "person", 0.9, i * 1000, 0.2, 0.3, 0.3, 0.45) for i in range(4)]
    assert "ZONE_ENTRY" not in types(build_events(obs, job([rule])))


def test_loitering_needs_continuous_time_in_zone():
    rule = {"id": 6, "type": "LOITERING", "zoneId": 1, "params": {"minSeconds": 30}}
    inside = [(0.2, 0.8)]
    long_stay = walk(1, "person", inside * 31)            # 0 s .. 30 s inside
    short_stay = walk(2, "person", inside * 30)           # 0 s .. 29 s inside
    broken = walk(3, "person", inside * 20 + [(0.8, 0.8)] + inside * 20)  # two 19 s stays

    events = build_events(long_stay + short_stay + broken, job([rule]))
    loiter = [e for e in events if e.eventType == "LOITERING"]
    assert [e.trackId for e in loiter] == [1]
    assert loiter[0].videoOffsetEndMs - loiter[0].videoOffsetStartMs == 30_000


def test_loitering_ignores_vehicles():
    rule = {"id": 6, "type": "LOITERING", "zoneId": 1, "params": {"minSeconds": 5}}
    obs = walk(1, "car", [(0.2, 0.8)] * 10)
    assert "LOITERING" not in types(build_events(obs, job([rule])))


def test_after_hours_vehicle_window_crosses_midnight_in_local_time():
    rule = {"id": 7, "type": "AFTER_HOURS_VEHICLE",
            "params": {"start": "22:00", "end": "06:00", "timezone": "America/Toronto"}}
    # 02:30 UTC on 4 Oct is 22:30 the previous evening in Toronto (UTC-4): inside the window.
    late = build_events(walk(1, "car", [(0.8, 0.8)] * 3), job([rule], recorded="2026-10-04T02:30:00Z"))
    # 16:00 UTC is noon in Toronto: outside.
    noon = build_events(walk(1, "car", [(0.8, 0.8)] * 3), job([rule], recorded="2026-10-04T16:00:00Z"))
    person = build_events(walk(1, "person", [(0.8, 0.8)] * 3), job([rule], recorded="2026-10-04T02:30:00Z"))

    assert "AFTER_HOURS_VEHICLE" in types(late)
    assert "AFTER_HOURS_VEHICLE" not in types(noon)
    assert "AFTER_HOURS_VEHICLE" not in types(person)


def test_after_hours_starts_when_window_opens_mid_video():
    rule = {"id": 7, "type": "AFTER_HOURS_VEHICLE", "params": {"start": "22:00", "end": "06:00"}}
    # Video starts 21:59:58 UTC; observations at 0..4 s, so the window opens at offset 2000.
    obs = walk(1, "truck", [(0.8, 0.8)] * 5)
    [event] = [e for e in build_events(obs, job([rule], recorded="2026-10-04T21:59:58Z"))
               if e.eventType == "AFTER_HOURS_VEHICLE"]
    assert event.videoOffsetStartMs == 2000


def test_after_hours_with_zone_only_counts_time_in_zone():
    rule = {"id": 7, "type": "AFTER_HOURS_VEHICLE", "zoneId": 1, "params": {"start": "00:00", "end": "23:59"}}
    outside = build_events(walk(1, "car", [(0.8, 0.8)] * 3), job([rule]))
    inside = build_events(walk(1, "car", [(0.2, 0.8)] * 3), job([rule]))
    assert "AFTER_HOURS_VEHICLE" not in types(outside)
    assert "AFTER_HOURS_VEHICLE" in types(inside)


def test_events_are_sorted_by_start():
    a = walk(1, "person", [(0.8, 0.3)] * 3, step_ms=1000)
    b = [Observation(2, "car", 0.9, 500 + i * 1000, 0.6, 0.1, 0.7, 0.3) for i in range(3)]
    assert [e.trackId for e in build_events(b + a, job())] == [1, 2]
