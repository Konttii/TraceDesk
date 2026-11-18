package com.tracedesk.config;

import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.UserStatus;
import com.tracedesk.repository.UserRepository;
import com.tracedesk.web.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, UserRepository userRepository) throws Exception {
        AuthenticationEntryPoint authEntryPoint = (request, response, authException) -> {
            ErrorCode errorCode = ErrorCode.UNAUTHORIZED;
            String message = "Требуется аутентификация";

            Throwable cause = authException != null ? authException.getCause() : null;
            if (authException instanceof LockedException || cause instanceof LockedException) {
                errorCode = ErrorCode.ACCOUNT_LOCKED;
                message = "Аккаунт временно заблокирован на 1 час из-за 3 неверных попыток ввода пароля";
            } else if (authException instanceof DisabledException || cause instanceof DisabledException) {
                User user = resolveUserFromBasicAuth(request, userRepository);
                if (user != null && user.getStatus() == UserStatus.PENDING_APPROVAL) {
                    errorCode = ErrorCode.ACCOUNT_PENDING_APPROVAL;
                    message = "Ваша заявка еще находится на рассмотрении у администратора";
                } else {
                    errorCode = ErrorCode.ACCOUNT_BLOCKED;
                    message = "Доступ к системе заблокирован администратором";
                }
            }

            writeJsonError(response, errorCode.getStatus().value(), errorCode.name(), message, request.getRequestURI());
        };

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/favicon.ico",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/api/dictionaries/**",
                                "/auth/register/**",
                                "/auth/password-reset/**"
                        ).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .httpBasic(basic -> basic.authenticationEntryPoint(authEntryPoint))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authEntryPoint)
                        .accessDeniedHandler((request, response, accessDeniedException) -> writeJsonError(
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                ErrorCode.ACCESS_DENIED.name(),
                                "Недостаточно прав для выполнения операции",
                                request.getRequestURI()
                        )));

        return http.build();
    }

    private User resolveUserFromBasicAuth(HttpServletRequest request, UserRepository userRepository) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String base64Token = header.substring(6).trim();
            String decoded = new String(Base64.getDecoder().decode(base64Token), StandardCharsets.UTF_8);
            int colonIndex = decoded.indexOf(':');
            if (colonIndex == -1) {
                return null;
            }
            String login = decoded.substring(0, colonIndex).trim();
            if (login.isEmpty()) {
                return null;
            }
            return userRepository.findByIin(login)
                    .or(() -> userRepository.findByUsername(login))
                    .orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void writeJsonError(
            HttpServletResponse response,
            int status,
            String code,
            String message,
            String path
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String json = """
                {"timestamp":"%s","status":%d,"code":"%s","message":"%s","path":"%s"}
                """.formatted(OffsetDateTime.now(), status, code, message, path);
        response.getWriter().write(json);
    }
}