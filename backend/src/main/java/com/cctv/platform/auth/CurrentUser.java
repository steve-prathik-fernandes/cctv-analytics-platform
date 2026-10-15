package com.cctv.platform.auth;

import com.cctv.platform.user.Role;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The signed-in caller, read from the validated JWT. Declare it as a controller method
 * parameter to receive it; see {@link CurrentUserArgumentResolver}.
 */
public record CurrentUser(long id, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    static CurrentUser from(Jwt jwt) {
        return new CurrentUser(Long.parseLong(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString(TokenService.ROLE_CLAIM)));
    }
}
