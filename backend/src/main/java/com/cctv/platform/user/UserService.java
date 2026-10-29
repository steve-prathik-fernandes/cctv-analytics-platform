package com.cctv.platform.user;

import com.cctv.platform.audit.AuditAction;
import com.cctv.platform.audit.AuditService;
import com.cctv.platform.camera.Camera;
import com.cctv.platform.camera.CameraRepository;
import com.cctv.platform.common.ApiException;
import com.cctv.platform.user.UserDtos.UserCreate;
import com.cctv.platform.user.UserDtos.UserUpdate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class UserService {

    private final UserRepository users;
    private final CameraRepository cameras;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public UserService(UserRepository users, CameraRepository cameras, PasswordEncoder passwordEncoder,
                       AuditService audit) {
        this.users = users;
        this.cameras = cameras;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public Page<User> list(Pageable pageable) {
        return users.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public User get(long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
    }

    @Transactional
    public User create(UserCreate req, long actorId) {
        String email = normalizeEmail(req.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("A user with this email already exists.");
        }
        User user = users.save(new User(email, passwordEncoder.encode(req.password()),
                req.displayName().trim(), req.role()));
        audit.record(actorId, AuditAction.USER_CREATE, "user", user.getId(),
                Map.of("email", email, "role", req.role().name()));
        return user;
    }

    @Transactional
    public User update(long id, UserUpdate req, long actorId) {
        User user = get(id);
        Map<String, Object> changes = new LinkedHashMap<>();

        boolean losesAdmin = user.getRole() == Role.ADMIN && user.isEnabled()
                && ((req.role() != null && req.role() != Role.ADMIN) || Boolean.FALSE.equals(req.enabled()));
        if (losesAdmin && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw ApiException.conflict("The last active admin cannot be disabled or demoted.");
        }

        if (req.displayName() != null && !req.displayName().trim().equals(user.getDisplayName())) {
            user.setDisplayName(req.displayName().trim());
            changes.put("displayName", user.getDisplayName());
        }
        if (req.role() != null && req.role() != user.getRole()) {
            user.setRole(req.role());
            changes.put("role", req.role().name());
        }
        if (req.enabled() != null && req.enabled() != user.isEnabled()) {
            user.setEnabled(req.enabled());
            changes.put("enabled", req.enabled());
        }
        if (req.password() != null) {
            user.setPasswordHash(passwordEncoder.encode(req.password()));
            changes.put("passwordChanged", true); // never the password itself
        }

        if (!changes.isEmpty()) {
            audit.record(actorId, AuditAction.USER_UPDATE, "user", user.getId(), changes);
        }
        return user;
    }

    @Transactional(readOnly = true)
    public List<Long> cameraIds(long id) {
        return get(id).getCameras().stream().map(Camera::getId).sorted().toList();
    }

    @Transactional
    public List<Long> replaceCameras(long id, List<Long> cameraIds, long actorId) {
        User user = get(id);
        List<Long> distinct = cameraIds.stream().distinct().sorted().toList();
        List<Camera> found = cameras.findAllById(distinct);
        if (found.size() != distinct.size()) {
            throw ApiException.notFound("One or more cameras do not exist.");
        }
        user.getCameras().clear();
        user.getCameras().addAll(new HashSet<>(found));
        audit.record(actorId, AuditAction.USER_CAMERAS_UPDATE, "user", user.getId(),
                Map.of("cameraIds", distinct));
        return distinct;
    }
}
