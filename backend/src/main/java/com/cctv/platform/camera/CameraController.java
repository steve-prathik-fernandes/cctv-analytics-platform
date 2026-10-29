package com.cctv.platform.camera;

import com.cctv.platform.auth.CurrentUser;
import com.cctv.platform.video.CameraDeletionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cameras")
public class CameraController {

    private final CameraService cameras;
    private final CameraDeletionService deletion;

    public CameraController(CameraService cameras, CameraDeletionService deletion) {
        this.cameras = cameras;
        this.deletion = deletion;
    }

    record CameraInput(@NotBlank @Size(max = 120) String name, @Size(max = 200) String location) {
    }

    @GetMapping
    public List<Camera> list(CurrentUser user) {
        return cameras.listVisible(user);
    }

    @GetMapping("/{cameraId}")
    public Camera get(@PathVariable long cameraId, CurrentUser user) {
        return cameras.getVisible(cameraId, user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public Camera create(@Valid @RequestBody CameraInput input, CurrentUser user) {
        return cameras.create(input.name(), input.location(), user);
    }

    @PutMapping("/{cameraId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Camera update(@PathVariable long cameraId, @Valid @RequestBody CameraInput input, CurrentUser user) {
        return cameras.update(cameraId, input.name(), input.location(), user);
    }

    @DeleteMapping("/{cameraId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable long cameraId, @RequestParam(defaultValue = "false") boolean deleteFootage,
                       CurrentUser user) {
        deletion.delete(cameraId, deleteFootage, user);
    }
}
