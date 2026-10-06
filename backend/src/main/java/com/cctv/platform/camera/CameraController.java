package com.cctv.platform.camera;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cameras")
public class CameraController {

    private final CameraRepository cameras;

    public CameraController(CameraRepository cameras) {
        this.cameras = cameras;
    }

    @GetMapping
    public List<Camera> list() {
        return cameras.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Camera create(@Valid @RequestBody Camera camera) {
        return cameras.save(camera);
    }
}
