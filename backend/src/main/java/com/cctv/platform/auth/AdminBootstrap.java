package com.cctv.platform.auth;

import com.cctv.platform.user.Role;
import com.cctv.platform.user.User;
import com.cctv.platform.user.UserRepository;
import com.cctv.platform.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first admin when the users table is empty, from {@code app.auth.bootstrap-admin}
 * (environment variables ADMIN_EMAIL and ADMIN_PASSWORD outside the dev profile). Without it a
 * fresh deployment would have nobody able to log in.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties props;

    AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, AuthProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;

        AuthProperties.BootstrapAdmin admin = props.bootstrapAdmin();
        if (admin == null || isBlank(admin.email()) || isBlank(admin.password())) {
            log.warn("No users exist and no bootstrap admin is configured. "
                    + "Set ADMIN_EMAIL and ADMIN_PASSWORD to create the first admin.");
            return;
        }
        if (admin.password().length() < 12) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least 12 characters.");
        }
        String email = UserService.normalizeEmail(admin.email());
        users.save(new User(email, passwordEncoder.encode(admin.password()), "Administrator", Role.ADMIN));
        log.info("Created bootstrap admin {}", email);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
