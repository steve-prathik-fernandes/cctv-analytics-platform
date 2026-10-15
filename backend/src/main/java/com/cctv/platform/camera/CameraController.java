package com.cctv.platform.camera;

import com.cctv.platform.auth.CurrentUser;
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

    public CameraController(CameraService cameras) {
        this.cameras = cameras;
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
}
