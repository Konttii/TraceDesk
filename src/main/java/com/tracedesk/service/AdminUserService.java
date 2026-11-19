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
import java.util.regex.Pattern;

@Service
public class AdminUserService {

    private static final List<Role> STAFF_ROLES = List.of(Role.INVESTIGATOR, Role.SUPPORT);
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "^[A-Za-zА-Яа-яЁёӘәҒғҚқҢңӨөҰұҮүҺһІі]+(?:[\\s-][A-Za-zА-Яа-яЁёӘәҒғҚқҢңӨөҰұҮүҺһІі]+)*$"
    );

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
    public AdminUserResponse rejectRegistration(Long id, User currentAdmin) {
        User targetUser = getUserOrThrow(id);
        if (currentAdmin != null && targetUser.getId().equals(currentAdmin.getId())) {
            throw new ApiException(
                    ErrorCode.SELF_ACTION_FORBIDDEN,
                    "Вы не можете отклонить собственную учетную запись"
            );
        }
        ensureNotLastActiveAdmin(targetUser);
        if (targetUser.getStatus() != UserStatus.PENDING_APPROVAL) {
            throw new ApiException(
                    ErrorCode.INVALID_USER_STATUS,
                    "Отклонить можно только заявку в статусе PENDING_APPROVAL (текущий статус: " + targetUser.getStatus() + ")"
            );
        }

        targetUser.setStatus(UserStatus.REJECTED);
        User saved = userRepository.save(targetUser);

        String email = saved.getEmail();
        String firstName = saved.getFirstName() != null ? saved.getFirstName() : saved.getFullName();
        String iin = saved.getIin() != null ? saved.getIin() : saved.getUsername();

        if (email != null && !email.isBlank()) {
            publishAfterCommit(() -> emailNotificationProducer.sendAccountDecisionEmail(email, firstName, iin, "REJECT"));
        }

        return toAdminUserResponse(saved);
    }

    @Transactional
    public AdminUserResponse updateUserProfile(Long id, UpdateUserProfileRequest request, User currentAdmin) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Данные для обновления профиля не могут быть пустыми");
        }

        User targetUser = getUserOrThrow(id);

        if (request.role() != null && request.role() != Role.ADMIN) {
            ensureNotLastActiveAdmin(targetUser);
            if (currentAdmin != null && targetUser.getId().equals(currentAdmin.getId())) {
                throw new ApiException(
                        ErrorCode.SELF_ACTION_FORBIDDEN,
                        "Вы не можете изменить собственную роль администратора"
                );
            }
        }

        if (request.firstName() != null) {
            targetUser.setFirstName(validatePersonName(request.firstName(), "Имя"));
        }
        if (request.lastName() != null) {
            targetUser.setLastName(validatePersonName(request.lastName(), "Фамилия"));
        }
        if (request.firstName() != null || request.lastName() != null) {
            String last = targetUser.getLastName() != null ? targetUser.getLastName() : "";
            String first = targetUser.getFirstName() != null ? targetUser.getFirstName() : "";
            String combined = (last + " " + first).trim();
            if (!combined.isEmpty()) {
                targetUser.setFullName(combined);
            }
        }

        if (request.role() != null) {
            targetUser.setRole(request.role());
        }

        if (request.regionId() != null) {
            String regionId = request.regionId().trim();
            Region region = regionRepository.findById(regionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Указанный регион не найден в справочнике"));
            targetUser.setRegion(region);
        }

        if (request.departmentId() != null) {
            Department department = departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Указанное следственное управление не найдено в справочнике"));
            targetUser.setDepartment(department);
        }

        if (request.positionId() != null) {
            Position position = positionRepository.findById(request.positionId())
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Указанная должность не найдена в справочнике"));
            targetUser.setPosition(position);
        }

        User saved = userRepository.save(targetUser);
        return toAdminUserResponse(saved);
    }

    @Transactional
    public AdminUserResponse blockUser(Long id, User currentAdmin) {
        User targetUser = getUserOrThrow(id);
        if (currentAdmin != null && targetUser.getId().equals(currentAdmin.getId())) {
            throw new ApiException(
                    ErrorCode.SELF_ACTION_FORBIDDEN,
                    "Вы не можете заблокировать собственную учетную запись"
            );
        }
        ensureNotLastActiveAdmin(targetUser);
        targetUser.setStatus(UserStatus.BLOCKED);
        User saved = userRepository.save(targetUser);
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

    private void ensureNotLastActiveAdmin(User targetUser) {
        if (targetUser.getRole() == Role.ADMIN
                && targetUser.getStatus() == UserStatus.ACTIVE
                && userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE) <= 1) {
            throw new ApiException(
                    ErrorCode.LAST_ADMIN_PROTECTION,
                    "Невозможно заблокировать или понизить роль единственного активного администратора в системе"
            );
        }
    }

    private User getUserOrThrow(Long id) {
        return userRepository.findWithProfileById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "Пользователь с ID " + id + " не найден"));
    }

    private String validatePersonName(String value, String fieldLabel) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, fieldLabel + " не может быть пустым");
        }
        String trimmed = value.trim();
        if (trimmed.length() < 2 || trimmed.length() > 50) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, fieldLabel + " должно содержать от 2 до 50 символов");
        }
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    fieldLabel + " должно содержать только буквы (без цифр и спецсимволов)"
            );
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
