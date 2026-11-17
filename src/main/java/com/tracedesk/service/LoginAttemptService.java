package com.tracedesk.service;

import com.tracedesk.config.AppUserPrincipal;
import com.tracedesk.domain.entity.User;
import com.tracedesk.repository.UserRepository;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

@Service
public class LoginAttemptService {

    public static final int MAX_FAILED_ATTEMPTS = 3;
    public static final long LOCK_DURATION_HOURS = 1;

    private final UserRepository userRepository;

    public LoginAttemptService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @EventListener
    @Transactional
    public void onAuthenticationFailure(AuthenticationFailureBadCredentialsEvent event) {
        findUser(event.getAuthentication()).ifPresent(user -> {
            if (user.getLockedUntil() != null && !user.getLockedUntil().isAfter(OffsetDateTime.now())) {
                user.setLockedUntil(null);
            }
            int attempts = user.getFailedLoginAttempts() + 1;
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                user.setLockedUntil(OffsetDateTime.now().plusHours(LOCK_DURATION_HOURS));
                user.setFailedLoginAttempts(0);
            } else {
                user.setFailedLoginAttempts(attempts);
            }
            userRepository.save(user);
        });
    }

    @EventListener
    @Transactional
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        findUser(authentication).ifPresent(user -> {
            if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
                user.setFailedLoginAttempts(0);
                user.setLockedUntil(null);
                userRepository.save(user);
            }
        });
        if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal
                && principal.getUser() != null) {
            principal.getUser().setFailedLoginAttempts(0);
            principal.getUser().setLockedUntil(null);
        }
    }

    private Optional<User> findUser(Authentication authentication) {
        if (authentication == null) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AppUserPrincipal appUserPrincipal && appUserPrincipal.getUser() != null) {
            User u = appUserPrincipal.getUser();
            if (u.getIin() != null) {
                Optional<User> byIin = userRepository.findByIin(u.getIin());
                if (byIin.isPresent()) {
                    return byIin;
                }
            }
            if (u.getUsername() != null) {
                return userRepository.findByUsername(u.getUsername());
            }
        }
        String login = authentication.getName();
        if (login == null || login.isBlank()) {
            return Optional.empty();
        }
        String normalized = login.trim();
        return userRepository.findByIin(normalized)
                .or(() -> userRepository.findByUsername(normalized));
    }
}
