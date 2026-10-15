package com.cctv.platform;

import com.cctv.platform.audit.AuditLog;
import com.cctv.platform.audit.AuditLogRepository;
import com.cctv.platform.camera.Camera;
import com.cctv.platform.camera.CameraRepository;
import com.cctv.platform.user.Role;
import com.cctv.platform.user.User;
import com.cctv.platform.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AuthAndAccessTests {

    static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired CameraRepository cameras;
    @Autowired AuditLogRepository auditLogs;
    @Autowired PasswordEncoder passwordEncoder;

    User admin;
    User operator;

    @BeforeEach
    void setUp() {
        // Unique emails keep the in-memory login limiter independent between tests.
        admin = createUser("admin-" + UUID.randomUUID() + "@test.local", Role.ADMIN);
        operator = createUser("op-" + UUID.randomUUID() + "@test.local", Role.OPERATOR);
    }

    // --- Login ---

    @Test
    void loginReturnsTokenAndUser() throws Exception {
        login(operator.getEmail(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(3600))
                .andExpect(jsonPath("$.user.role").value("OPERATOR"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());

        assertThat(lastAudit().getAction()).isEqualTo("LOGIN_SUCCESS");
    }

    @Test
    void loginIgnoresEmailCase() throws Exception {
        login(operator.getEmail().toUpperCase(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameGenericError() throws Exception {
        String wrongPassword = login(operator.getEmail(), "wrong-password-123")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nobody-" + UUID.randomUUID() + "@test.local", "wrong-password-123")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(wrongPassword).get("detail"))
                .isEqualTo(json.readTree(unknownEmail).get("detail"));
        assertThat(lastAudit().getAction()).isEqualTo("LOGIN_FAILURE");
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        operator.setEnabled(false);
        login(operator.getEmail(), PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedFailuresAreBlocked() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(operator.getEmail(), "wrong-password-123").andExpect(status().isUnauthorized());
        }
        // Even the right password is refused while blocked.
        login(operator.getEmail(), PASSWORD).andExpect(status().isTooManyRequests());
        assertThat(lastAudit().getAction()).isEqualTo("LOGIN_BLOCKED");
    }

    @Test
    void passwordsAreStoredAsBcryptHashes() {
        assertThat(operator.getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
    }

    // --- Tokens ---

    @Test
    void apiRequiresToken() throws Exception {
        mvc.perform(get("/api/cameras"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = tokenFor(operator);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        mvc.perform(get("/api/cameras").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(operator.getEmail()));
    }

    @Test
    void internalEndpointsAreClosed() throws Exception {
        mvc.perform(post("/internal/jobs/1/status").header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());
    }

    // --- Roles ---

    @Test
    void operatorCannotUseAdminEndpoints() throws Exception {
        String auth = bearer(operator);
        mvc.perform(post("/api/cameras").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Gate\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-logs").header("Authorization", auth)).andExpect(status().isForbidden());
    }

    @Test
    void adminCreatesCameraAndItIsAudited() throws Exception {
        mvc.perform(post("/api/cameras").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Gate\",\"location\":\"North\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Gate"));

        AuditLog entry = lastAudit();
        assertThat(entry.getAction()).isEqualTo("CAMERA_CREATE");
        assertThat(entry.getUserId()).isEqualTo(admin.getId());
    }

    // --- Camera access ---

    @Test
    void operatorSeesOnlyAssignedCameras() throws Exception {
        Camera assigned = createCamera("Assigned");
        Camera other = createCamera("Other");

        mvc.perform(put("/api/users/" + operator.getId() + "/cameras").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraIds\":[" + assigned.getId() + "]}"))
                .andExpect(status().isOk());

        String auth = bearer(operator);
        String body = mvc.perform(get("/api/cameras").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Long> ids = json.readTree(body).findValues("id").stream().map(JsonNode::asLong).toList();
        assertThat(ids).containsExactly(assigned.getId());

        mvc.perform(get("/api/cameras/" + assigned.getId()).header("Authorization", auth))
                .andExpect(status().isOk());
        // Not assigned: reported as not found, so the operator learns nothing about it.
        mvc.perform(get("/api/cameras/" + other.getId()).header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminSeesAllCameras() throws Exception {
        Camera a = createCamera("A");
        Camera b = createCamera("B");
        String body = mvc.perform(get("/api/cameras").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Long> ids = json.readTree(body).findValues("id").stream().map(JsonNode::asLong).toList();
        assertThat(ids).contains(a.getId(), b.getId());
    }

    // --- User management ---

    @Test
    void adminCreatesUser() throws Exception {
        String email = "new-" + UUID.randomUUID() + "@test.local";
        mvc.perform(post("/api/users").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"a-long-enough-password","displayName":"New","role":"OPERATOR"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));

        login(email, "a-long-enough-password").andExpect(status().isOk());
    }

    @Test
    void shortPasswordIsRejectedWithFieldError() throws Exception {
        mvc.perform(post("/api/users").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@test.local\",\"password\":\"short\",\"displayName\":\"X\",\"role\":\"OPERATOR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void duplicateEmailIsConflict() throws Exception {
        mvc.perform(post("/api/users").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"a-long-enough-password","displayName":"Dup","role":"OPERATOR"}
                                """.formatted(operator.getEmail())))
                .andExpect(status().isConflict());
    }

    @Test
    void lastActiveAdminCannotBeDisabled() throws Exception {
        // Leave exactly one enabled admin.
        users.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN && !u.getId().equals(admin.getId()))
                .forEach(u -> u.setEnabled(false));
        users.flush();

        mvc.perform(patch("/api/users/" + admin.getId()).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/users/" + admin.getId()).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OPERATOR\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void passwordChangeIsAuditedWithoutThePassword() throws Exception {
        mvc.perform(patch("/api/users/" + operator.getId()).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"a-brand-new-password\"}"))
                .andExpect(status().isOk());

        AuditLog entry = lastAudit();
        assertThat(entry.getAction()).isEqualTo("USER_UPDATE");
        assertThat(entry.getDetails()).containsEntry("passwordChanged", true);
        assertThat(String.valueOf(entry.getDetails())).doesNotContain("a-brand-new-password");
    }

    @Test
    void adminReadsAuditLogFilteredByAction() throws Exception {
        login(operator.getEmail(), PASSWORD).andExpect(status().isOk());
        mvc.perform(get("/api/audit-logs").param("action", "LOGIN_SUCCESS").param("userId",
                                String.valueOf(operator.getId()))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.content[0].userId").value(operator.getId()));
    }

    // --- Helpers ---

    private User createUser(String email, Role role) {
        return users.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), "Test " + role, role));
    }

    private Camera createCamera(String name) {
        Camera camera = new Camera();
        camera.setName(name);
        return cameras.saveAndFlush(camera);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", password))));
    }

    private String tokenFor(User user) throws Exception {
        String body = login(user.getEmail(), PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    private String bearer(User user) throws Exception {
        return "Bearer " + tokenFor(user);
    }

    private AuditLog lastAudit() {
        return auditLogs.findAll().stream().reduce((a, b) -> b).orElseThrow();
    }
}
