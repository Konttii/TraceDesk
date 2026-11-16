package com.tracedesk.repository;

import com.tracedesk.domain.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = {"region", "department", "position"})
    Optional<User> findByUsername(String username);

    @EntityGraph(attributePaths = {"region", "department", "position"})
    Optional<User> findByIin(String iin);

    @EntityGraph(attributePaths = {"region", "department", "position"})
    Optional<User> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = {"region", "department", "position"})
    Optional<User> findWithProfileById(Long id);

    boolean existsByIin(String iin);

    boolean existsByEmailIgnoreCase(String email);
}
