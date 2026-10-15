package com.cctv.platform.auth;

import com.cctv.platform.user.UserDtos.UserResponse;
import com.cctv.platform.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final TokenService tokens;
    private final UserService users;

    public AuthController(AuthService auth, TokenService tokens, UserService users) {
        this.auth = auth;
        this.tokens = tokens;
        this.users = users;
    }

    record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {
    }

    record LoginResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        AuthService.LoginResult result = auth.login(req.email(), req.password());
        return new LoginResponse(result.accessToken(), "Bearer", tokens.ttlSeconds(),
                UserResponse.of(result.user()));
    }

    @GetMapping("/me")
    public UserResponse me(CurrentUser current) {
        return UserResponse.of(users.get(current.id()));
    }
}
