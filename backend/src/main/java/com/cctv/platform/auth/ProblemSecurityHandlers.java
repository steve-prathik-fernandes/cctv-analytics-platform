package com.cctv.platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** Writes 401 and 403 responses in the same RFC 7807 format as other API errors. */
final class ProblemSecurityHandlers {

    private ProblemSecurityHandlers() {
    }

    static AuthenticationEntryPoint unauthorized(ObjectMapper json) {
        return (request, response, ex) -> write(response, json, HttpStatus.UNAUTHORIZED,
                "Sign in required, or the token is invalid or expired.");
    }

    static AccessDeniedHandler forbidden(ObjectMapper json) {
        return (request, response, ex) -> write(response, json, HttpStatus.FORBIDDEN,
                "You do not have permission to do this.");
    }

    private static void write(HttpServletResponse response, ObjectMapper json, HttpStatus status, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
