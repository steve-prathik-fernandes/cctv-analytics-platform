package com.cctv.platform.auth;

import com.cctv.platform.audit.AuditAction;
import com.cctv.platform.audit.AuditService;
import com.cctv.platform.common.ApiException;
import com.cctv.platform.user.User;
import com.cctv.platform.user.UserRepository;
import com.cctv.platform.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid email or password.";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final LoginAttemptLimiter limiter;
    private final AuditService audit;
    private final Clock clock;
    /** Compared against when the email is unknown, so both cases take about the same time. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                       LoginAttemptLimiter limiter, AuditService audit, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.limiter = limiter;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password-used-for-timing");
    }

    public record LoginResult(String accessToken, User user) {
    }

    /**
     * Not transactional on purpose: each audit entry is committed on its own, so failed
     * attempts are recorded even though the request ends in an error.
     */
    public LoginResult login(String rawEmail, String password) {
        String email = UserService.normalizeEmail(rawEmail);

        if (limiter.isBlocked(email)) {
            audit.record(null, AuditAction.LOGIN_BLOCKED, "user", null, Map.of("email", email));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Try again later.");
        }

        Optional<User> found = users.findByEmail(email);
        boolean passwordOk = passwordEncoder.matches(password, found.map(User::getPasswordHash).orElse(dummyHash));

        if (found.isEmpty() || !passwordOk) {
            limiter.recordFailure(email);
            audit.record(found.map(User::getId).orElse(null), AuditAction.LOGIN_FAILURE, "user",
                    found.map(User::getId).orElse(null), Map.of("email", email, "reason", "BAD_CREDENTIALS"));
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }

        User user = found.get();
        if (!user.isEnabled()) {
            // The password was right, so saying why does not help someone guessing passwords.
            audit.record(user.getId(), AuditAction.LOGIN_FAILURE, "user", user.getId(),
                    Map.of("email", email, "reason", "DISABLED"));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "This account is disabled.");
        }

        limiter.reset(email);
        user.setLastLoginAt(clock.instant());
        users.save(user);
        audit.record(user.getId(), AuditAction.LOGIN_SUCCESS, "user", user.getId(), null);
        return new LoginResult(tokens.issue(user), user);
    }
}
