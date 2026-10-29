package com.cctv.platform.user;

import com.cctv.platform.auth.CurrentUser;
import com.cctv.platform.common.PageResponse;
import com.cctv.platform.user.UserDtos.CameraAccess;
import com.cctv.platform.user.UserDtos.UserCreate;
import com.cctv.platform.user.UserDtos.UserResponse;
import com.cctv.platform.user.UserDtos.UserUpdate;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("id"));
        return PageResponse.of(users.list(pageable), UserResponse::of);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody UserCreate req, CurrentUser actor) {
        return UserResponse.of(users.create(req, actor.id()));
    }

    @PatchMapping("/{userId}")
    public UserResponse update(@PathVariable long userId, @Valid @RequestBody UserUpdate req, CurrentUser actor) {
        return UserResponse.of(users.update(userId, req, actor.id()));
    }

    @GetMapping("/{userId}/cameras")
    public Map<String, Object> cameras(@PathVariable long userId) {
        return Map.of("cameraIds", users.cameraIds(userId));
    }

    @PutMapping("/{userId}/cameras")
    public Map<String, Object> replaceCameras(@PathVariable long userId, @Valid @RequestBody CameraAccess req,
                                              CurrentUser actor) {
        return Map.of("cameraIds", users.replaceCameras(userId, req.cameraIds(), actor.id()));
    }
}
