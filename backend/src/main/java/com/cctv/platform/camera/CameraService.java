package com.cctv.platform.camera;

import com.cctv.platform.audit.AuditAction;
import com.cctv.platform.audit.AuditService;
import com.cctv.platform.auth.CurrentUser;
import com.cctv.platform.common.ApiException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Camera access rules (FR-04): admins see every camera, operators only the ones assigned to them. */
@Service
public class CameraService {

    private final CameraRepository cameras;
    private final AuditService audit;

    public CameraService(CameraRepository cameras, AuditService audit) {
        this.cameras = cameras;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Camera> listVisible(CurrentUser user) {
        return user.isAdmin() ? cameras.findAll(Sort.by("id")) : cameras.findAccessibleBy(user.id());
    }

    /**
     * Returns the camera if the caller may see it. A camera the caller may not see is reported
     * as not found, so operators cannot discover which camera IDs exist.
     */
    @Transactional(readOnly = true)
    public Camera getVisible(long cameraId, CurrentUser user) {
        if (!user.isAdmin() && !cameras.isAccessibleBy(user.id(), cameraId)) {
            throw ApiException.notFound("Camera not found.");
        }
        return cameras.findById(cameraId).orElseThrow(() -> ApiException.notFound("Camera not found."));
    }

    @Transactional
    public Camera create(String name, String location, CurrentUser actor) {
        Camera camera = new Camera();
        camera.setName(name.trim());
        camera.setLocation(location == null || location.isBlank() ? null : location.trim());
        camera = cameras.save(camera);
        audit.record(actor.id(), AuditAction.CAMERA_CREATE, "camera", camera.getId(),
                Map.of("name", camera.getName()));
        return camera;
    }
}
