# Database Design

Status: Week 2 deliverable. Version 0.1, 2026-10-06.
Related: [requirements](01-requirements.md), [architecture](03-architecture.md).

## 1. Entity-relationship diagram

```mermaid
erDiagram
  users ||--o{ user_camera_access : "granted"
  cameras ||--o{ user_camera_access : "visible to"
  cameras ||--o{ zones : "defines"
  cameras ||--o{ event_rules : "has"
  zones |o--o{ event_rules : "scopes"
  cameras ||--o{ videos : "receives"
  users ||--o{ videos : "uploads"
  videos ||--o{ processing_jobs : "processed by"
  processing_jobs ||--o{ events : "produces"
  videos ||--o{ events : "contains"
  cameras ||--o{ events : "has"
  zones |o--o{ events : "triggered in"
  event_rules |o--o{ events : "triggered by"
  users |o--o{ audit_logs : "performs"

  users {
    bigint id PK
    string email UK
    string password_hash
    string display_name
    string role "ADMIN or OPERATOR"
    boolean enabled
    timestamp created_at
    timestamp last_login_at
  }

  cameras {
    bigint id PK
    string name
    string location
    timestamp created_at
  }

  user_camera_access {
    bigint user_id PK, FK
    bigint camera_id PK, FK
  }

  zones {
    bigint id PK
    bigint camera_id FK
    string name
    json polygon "normalized 0..1 points"
  }

  event_rules {
    bigint id PK
    bigint camera_id FK
    bigint zone_id FK "nullable"
    string rule_type "LOITERING, ZONE_ENTRY, AFTER_HOURS_VEHICLE"
    json params
    boolean enabled
  }

  videos {
    bigint id PK
    bigint camera_id FK
    bigint uploaded_by FK
    string original_filename
    string storage_key UK
    bigint size_bytes
    float duration_seconds
    timestamp recorded_start_at
    timestamp uploaded_at
  }

  processing_jobs {
    bigint id PK
    bigint video_id FK
    string status "QUEUED, RUNNING, DONE, FAILED"
    string model_name
    int frame_stride
    string error_message
    int event_count
    timestamp created_at
    timestamp started_at
    timestamp finished_at
  }

  events {
    bigint id PK
    bigint camera_id FK
    bigint video_id FK
    bigint job_id FK
    bigint zone_id FK "nullable"
    bigint rule_id FK "nullable"
    string event_type "OBJECT_DETECTED, LOITERING, ZONE_ENTRY, AFTER_HOURS_VEHICLE"
    string object_label "person, car, truck, bus, motorcycle"
    int track_id
    float confidence
    timestamp started_at
    timestamp ended_at
    int video_offset_start_ms
    int video_offset_end_ms
    json bbox
    string thumbnail_key
    string clip_key
  }

  audit_logs {
    bigint id PK
    bigint user_id FK "nullable"
    string action
    string resource_type
    bigint resource_id
    string ip_address
    json details
    timestamp created_at
  }
```

## 2. Table notes

| Table | Notes |
|---|---|
| `users` | Email is unique. Role is `ADMIN` or `OPERATOR`. Disabled users cannot log in. |
| `cameras` | A named logical source. There is no stream URL because input is uploaded footage. |
| `user_camera_access` | Join table. An operator sees a camera only if a row exists. Admins see all cameras. |
| `zones` | Polygon stored as JSON points normalized to 0..1, independent of video resolution. |
| `event_rules` | One row per enabled rule. `params` holds rule settings, for example `{"minSeconds":30}` or `{"start":"22:00","end":"06:00"}`. |
| `videos` | `recorded_start_at` is the time the footage was captured, supplied at upload. All event times derive from it. `storage_key` is generated, never the user's file name. |
| `processing_jobs` | One row per processing run. Keeps status, errors and which model was used, so results can be traced and re-run. |
| `events` | One row per tracked object or triggered rule. `started_at` equals the video's `recorded_start_at` plus the offset. `camera_id` is stored on the event for fast filtering. |
| `audit_logs` | Append-only. `user_id` is nullable for failed logins where no user matched. |

## 3. Indexes

| Index | Serves |
|---|---|
| `events (camera_id, started_at DESC)` | The main search: events for a camera in a time range |
| `events (event_type, started_at DESC)` | Search by event type |
| `events (object_label)` | Filter by person or vehicle type |
| `events (job_id)` | Replace or delete a job's events |
| `videos (camera_id, uploaded_at DESC)` | Footage list per camera |
| `audit_logs (created_at DESC)` | Admin audit view |
| `audit_logs (user_id, created_at DESC)` | Audit view filtered by user |
| `users (email)` unique | Login lookup |

## 4. Integrity and deletion rules

- Deleting a video removes its jobs and events (cascade) and the API removes its stored clips and thumbnails. The deletion is written to the audit log first.
- Deleting a camera is blocked while it has videos, unless the admin confirms deleting them too.
- The last active admin cannot be disabled or deleted.
- Audit rows are never updated or deleted by the application.

## 5. Changes needed to the current scaffold

The scaffold's `Camera` entity has `sourceUrl`, which does not fit the uploaded-footage design. Planned changes:

1. Remove `sourceUrl` from `Camera`.
2. Replace `spring.jpa.hibernate.ddl-auto: update` with Flyway migrations, starting with a baseline migration that creates the tables above.
3. Add entities and repositories for the remaining tables as each feature is built (users and access first, then videos, jobs, events, audit).
