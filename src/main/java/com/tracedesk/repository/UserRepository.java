package com.tracedesk.repository;

import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.UserStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
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

    @EntityGraph(attributePaths = {"region", "department", "position"})
    List<User> findByStatusOrderByCreatedAtAscIdAsc(UserStatus status);

    @EntityGraph(attributePaths = {"region", "department", "position"})
    List<User> findByRoleInOrderByCreatedAtDescIdDesc(Collection<Role> roles);

    boolean existsByIin(String iin);

    boolean existsByEmailIgnoreCase(String email);

    long countByRoleAndStatus(Role role, UserStatus status);
}
