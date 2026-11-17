package com.tracedesk.service;

import com.tracedesk.config.AppUserPrincipal;
import com.tracedesk.domain.entity.User;
import com.tracedesk.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        String normalizedLogin = login != null ? login.trim() : "";
        User user = userRepository.findByIin(normalizedLogin)
                .or(() -> userRepository.findByUsername(normalizedLogin))
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден: " + login));
        return new AppUserPrincipal(user);
    }
}
