package com.tracedesk.service;

import com.tracedesk.domain.entity.Department;
import com.tracedesk.domain.entity.Position;
import com.tracedesk.domain.entity.Region;
import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.UserStatus;
import com.tracedesk.repository.DepartmentRepository;
import com.tracedesk.repository.PositionRepository;
import com.tracedesk.repository.RegionRepository;
import com.tracedesk.repository.UserRepository;
import com.tracedesk.web.dto.AdminUserResponse;
import com.tracedesk.web.dto.DepartmentResponse;
import com.tracedesk.web.dto.PositionResponse;
import com.tracedesk.web.dto.RegionResponse;
import com.tracedesk.web.dto.UpdateUserProfileRequest;
import com.tracedesk.web.exception.ApiException;
import com.tracedesk.web.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
public class AdminUserService {

    private static final List<Role> STAFF_ROLES = List.of(Role.INVESTIGATOR, Role.SUPPORT);

    private final UserRepository userRepository;
    private final RegionRepository regionRepository;
    private final DepartmentRepository departmentRepository;
    private final PositionRepository positionRepository;
    private final EmailNotificationProducer emailNotificationProducer;

    public AdminUserService(
            UserRepository userRepository,
            RegionRepository regionRepository,
            DepartmentRepository departmentRepository,
            PositionRepository positionRepository,
            EmailNotificationProducer emailNotificationProducer
    ) {
        this.userRepository = userRepository;
        this.regionRepository = regionRepository;
        this.departmentRepository = departmentRepository;
        this.positionRepository = positionRepository;
        this.emailNotificationProducer = emailNotificationProducer;
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> getPendingRegistrations() {
        return userRepository.findByStatusOrderByCreatedAtAscIdAsc(UserStatus.PENDING_APPROVAL)
                .stream()
                .map(this::toAdminUserResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> getUsers(Role role, String regionId, UserStatus status) {
        String normalizedRegionId = (regionId != null && !regionId.isBlank()) ? regionId.trim() : null;
        return userRepository.findByRoleInOrderByCreatedAtDescIdDesc(STAFF_ROLES)
                .stream()
                .filter(u -> role == null || u.getRole() == role)
                .filter(u -> normalizedRegionId == null
                        || (u.getRegion() != null && normalizedRegionId.equals(u.getRegion().getId())))
                .filter(u -> status == null || u.getStatus() == status)
                .map(this::toAdminUserResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserResponse getUserDetails(Long id) {
        return toAdminUserResponse(getUserOrThrow(id));
    }

    @Transactional
    public AdminUserResponse approveRegistration(Long id) {
        User user = getUserOrThrow(id);
        if (user.getStatus() != UserStatus.PENDING_APPROVAL) {
            throw new ApiException(
                    ErrorCode.INVALID_USER_STATUS,
                    "Одобрить можно только заявку в статусе PENDING_APPROVAL (текущий статус: " + user.getStatus() + ")"
            );
        }

        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(user);

        String email = saved.getEmail();
        String firstName = saved.getFirstName() != null ? saved.getFirstName() : saved.getFullName();
        String iin = saved.getIin() != null ? saved.getIin() : saved.getUsername();

        if (email != null && !email.isBlank()) {
            publishAfterCommit(() -> emailNotificationProducer.sendAccountDecisionEmail(email, firstName, iin, "APPROVE"));
        }

        return toAdminUserResponse(saved);
    }

    @Transactional
    public AdminUserResponse rejectRegistration(Long id) {
        User user = getUserOrThrow(id);
        if (user.getStatus() != UserStatus.PENDING_APPROVAL) {
            throw new ApiException(
                    ErrorCode.INVALID_USER_STATUS,
                    "Отклонить можно только заявку в статусе PENDING_APPROVAL (текущий статус: " + user.getStatus() + ")"
            );
        }

        user.setStatus(UserStatus.REJECTED);
        User saved = userRepository.save(user);

        String email = saved.getEmail();
        String firstName = saved.getFirstName() != null ? saved.getFirstName() : saved.getFullName();
        String iin = saved.getIin() != null ? saved.getIin() : saved.getUsername();

        if (email != null && !email.isBlank()) {
            publishAfterCommit(() -> emailNotificationProducer.sendAccountDecisionEmail(email, firstName, iin, "REJECT"));
        }

        return toAdminUserResponse(saved);
    }

    @Transactional
    public AdminUserResponse updateUserProfile(Long id, UpdateUserProfileRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Данные для обновления профиля не могут быть пустыми");
        }

        User user = getUserOrThrow(id);

        if (request.firstName() != null) {
            user.setFirstName(requireNonBlank(request.firstName(), "Имя не может быть пустым", 50));
        }
        if (request.lastName() != null) {
            user.setLastName(requireNonBlank(request.lastName(), "Фамилия не может быть пустой", 50));
        }
        if (request.firstName() != null || request.lastName() != null) {
            String last = user.getLastName() != null ? user.getLastName() : "";
            String first = user.getFirstName() != null ? user.getFirstName() : "";
            String combined = (last + " " + first).trim();
            if (!combined.isEmpty()) {
                user.setFullName(combined);
            }
        }

        if (request.role() != null) {
            user.setRole(request.role());
        }

        if (request.regionId() != null) {
            String regionId = request.regionId().trim();
            Region region = regionRepository.findById(regionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Указанный регион не найден в справочнике"));
            user.setRegion(region);
        }

        if (request.departmentId() != null) {
            Department department = departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Указанное следственное управление не найдено в справочнике"));
            user.setDepartment(department);
        }

        if (request.positionId() != null) {
            Position position = positionRepository.findById(request.positionId())
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Указанная должность не найдена в справочнике"));
            user.setPosition(position);
        }

        User saved = userRepository.save(user);
        return toAdminUserResponse(saved);
    }

    @Transactional
    public AdminUserResponse blockUser(Long id, User currentAdmin) {
        User user = getUserOrThrow(id);
        if (currentAdmin != null && user.getId().equals(currentAdmin.getId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Нельзя заблокировать собственную учётную запись");
        }
        user.setStatus(UserStatus.BLOCKED);
        User saved = userRepository.save(user);
        return toAdminUserResponse(saved);
    }

    @Transactional
    public AdminUserResponse unblockUser(Long id) {
        User user = getUserOrThrow(id);
        user.setStatus(UserStatus.ACTIVE);
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        User saved = userRepository.save(user);
        return toAdminUserResponse(saved);
    }

    private User getUserOrThrow(Long id) {
        return userRepository.findWithProfileById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "Пользователь с ID " + id + " не найден"));
    }

    private String requireNonBlank(String value, String errorMessage, int maxLen) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, errorMessage);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLen) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, errorMessage + " (макс. " + maxLen + " символов)");
        }
        return trimmed;
    }

    private void publishAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private AdminUserResponse toAdminUserResponse(User user) {
        RegionResponse region = user.getRegion() != null
                ? new RegionResponse(user.getRegion().getId(), user.getRegion().getName())
                : null;
        DepartmentResponse department = user.getDepartment() != null
                ? new DepartmentResponse(user.getDepartment().getId(), user.getDepartment().getName())
                : null;
        PositionResponse position = user.getPosition() != null
                ? new PositionResponse(user.getPosition().getId(), user.getPosition().getName())
                : null;

        return new AdminUserResponse(
                user.getId(),
                user.getIin(),
                user.getEmail(),
                user.getFullName(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.getStatus(),
                region,
                department,
                position,
                user.getFailedLoginAttempts(),
                user.getLockedUntil(),
                user.getCreatedAt()
        );
    }
}
