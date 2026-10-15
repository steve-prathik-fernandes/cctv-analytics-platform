package com.cctv.platform.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request and response shapes for the user endpoints. Matches docs/api/openapi.yaml. */
public final class UserDtos {

    private UserDtos() {
    }

    public record UserResponse(Long id, String email, String displayName, Role role, boolean enabled,
                               Instant createdAt, Instant lastLoginAt) {
        public static UserResponse of(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(), u.isEnabled(),
                    u.getCreatedAt(), u.getLastLoginAt());
        }
    }

    // BCrypt only uses the first 72 bytes of a password, so longer ones are rejected.
    public record UserCreate(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotBlank @Size(max = 120) String displayName,
            @NotNull Role role) {
    }

    /** All fields optional; only the ones present are changed. */
    public record UserUpdate(
            @Size(min = 1, max = 120) String displayName,
            Role role,
            Boolean enabled,
            @Size(min = 12, max = 72) String password) {
    }

    public record CameraAccess(@NotNull List<@NotNull Long> cameraIds) {
    }
}
