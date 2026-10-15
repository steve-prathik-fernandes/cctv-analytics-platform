package com.cctv.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks the Flyway baseline against docs/04-database-design.md. */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class SchemaMigrationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsAllDesignedTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "users", "cameras", "user_camera_access", "zones", "event_rules",
                "videos", "processing_jobs", "events", "audit_logs");
    }

    @Test
    void rejectsUnknownRole() {
        assertThatThrownBy(() -> insertUser("x@example.com", "SUPERUSER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateEmail() {
        insertUser("dup@example.com", "OPERATOR");
        assertThatThrownBy(() -> insertUser("dup@example.com", "ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingVideoRemovesItsJobsAndEvents() {
        long videoId = insertVideoWithOneEvent();

        jdbc.update("DELETE FROM videos WHERE id = ?", videoId);

        assertThat(count("processing_jobs")).isZero();
        assertThat(count("events")).isZero();
    }

    @Test
    void cameraWithVideosCannotBeDeleted() {
        insertVideoWithOneEvent();
        long cameraId = jdbc.queryForObject("SELECT id FROM cameras", Long.class);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM cameras WHERE id = ?", cameraId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingZoneKeepsEventHistory() {
        insertVideoWithOneEvent();
        long cameraId = jdbc.queryForObject("SELECT id FROM cameras", Long.class);
        jdbc.update("INSERT INTO zones (camera_id, name, polygon) VALUES (?, 'Gate', '[[0,0],[1,0],[1,1]]')",
                cameraId);
        long zoneId = jdbc.queryForObject("SELECT id FROM zones", Long.class);
        jdbc.update("UPDATE events SET zone_id = ?", zoneId);

        jdbc.update("DELETE FROM zones WHERE id = ?", zoneId);

        assertThat(count("events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT zone_id FROM events", Long.class)).isNull();
    }

    private long insertVideoWithOneEvent() {
        long userId = insertUser("op@example.com", "OPERATOR");
        jdbc.update("INSERT INTO cameras (name) VALUES ('Dock')");
        long cameraId = jdbc.queryForObject("SELECT id FROM cameras", Long.class);
        jdbc.update("""
                INSERT INTO videos (camera_id, uploaded_by, original_filename, storage_key, size_bytes, recorded_start_at)
                VALUES (?, ?, 'clip.mp4', 'videos/abc.mp4', 1024, CURRENT_TIMESTAMP)""", cameraId, userId);
        long videoId = jdbc.queryForObject("SELECT id FROM videos", Long.class);
        jdbc.update("INSERT INTO processing_jobs (video_id, status, model_name) VALUES (?, 'DONE', 'yolov8n')",
                videoId);
        long jobId = jdbc.queryForObject("SELECT id FROM processing_jobs", Long.class);
        jdbc.update("""
                INSERT INTO events (camera_id, video_id, job_id, event_type, object_label, started_at, video_offset_start_ms)
                VALUES (?, ?, ?, 'OBJECT_DETECTED', 'person', CURRENT_TIMESTAMP, 0)""", cameraId, videoId, jobId);
        return videoId;
    }

    private long insertUser(String email, String role) {
        jdbc.update("INSERT INTO users (email, password_hash, display_name, role) VALUES (?, 'x', 'Test', ?)",
                email, role);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
