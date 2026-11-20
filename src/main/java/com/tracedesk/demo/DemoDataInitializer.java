package com.tracedesk.demo;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RegionRepository regionRepository;
    private final DepartmentRepository departmentRepository;
    private final PositionRepository positionRepository;
    private final PasswordEncoder passwordEncoder;
    private final String demoPassword;

    public DemoDataInitializer(
            UserRepository userRepository,
            RegionRepository regionRepository,
            DepartmentRepository departmentRepository,
            PositionRepository positionRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.demo.password:password}") String demoPassword
    ) {
        this.userRepository = userRepository;
        this.regionRepository = regionRepository;
        this.departmentRepository = departmentRepository;
        this.positionRepository = positionRepository;
        this.passwordEncoder = passwordEncoder;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(String... args) {
        upsertDemoUser(
                "admin",
                "000000000001",
                "admin@tracedesk.kz",
                "Администратор",
                "Системы",
                "Администратор Системы",
                Role.ADMIN,
                null,
                null,
                null
        );
        upsertDemoUser(
                "irina",
                "950101400001",
                "irina@tracedesk.kz",
                "Ирина",
                "Соколова",
                "Соколова Ирина",
                Role.INVESTIGATOR,
                "01",
                (short) 1,
                (short) 2
        );
        upsertDemoUser(
                "pavel",
                "940202300002",
                "pavel@tracedesk.kz",
                "Павел",
                "Морозов",
                "Морозов Павел",
                Role.INVESTIGATOR,
                "04",
                (short) 2,
                (short) 3
        );
        upsertDemoUser(
                "oleg",
                "930303300003",
                "oleg@tracedesk.kz",
                "Олег",
                "Смирнов",
                "Смирнов Олег",
                Role.SUPPORT,
                "01",
                null,
                null
        );
        upsertDemoUser(
                "anna",
                "960404400004",
                "anna@tracedesk.kz",
                "Анна",
                "Волкова",
                "Волкова Анна",
                Role.SUPPORT,
                "04",
                null,
                null
        );
    }

    private void upsertDemoUser(
            String username,
            String iin,
            String email,
            String firstName,
            String lastName,
            String fullName,
            Role role,
            String regionId,
            Short departmentId,
            Short positionId
    ) {
        Region region = regionId != null ? regionRepository.findById(regionId).orElse(null) : null;
        Department department = departmentId != null ? departmentRepository.findById(departmentId).orElse(null) : null;
        Position position = positionId != null ? positionRepository.findById(positionId).orElse(null) : null;

        User user = userRepository.findByUsername(username).orElseGet(() -> {
            User created = new User(username, fullName, passwordEncoder.encode(demoPassword), role);
            created.setStatus(UserStatus.ACTIVE);
            return created;
        });

        boolean changed = user.getId() == null;
        if (user.getIin() == null) {
            user.setIin(iin);
            changed = true;
        }
        if (user.getEmail() == null) {
            user.setEmail(email);
            changed = true;
        }
        if (user.getFirstName() == null) {
            user.setFirstName(firstName);
            changed = true;
        }
        if (user.getLastName() == null) {
            user.setLastName(lastName);
            changed = true;
        }
        if (user.getStatus() == null) {
            user.setStatus(UserStatus.ACTIVE);
            changed = true;
        }
        if (user.getRegion() == null && region != null) {
            user.setRegion(region);
            changed = true;
        }
        if (user.getDepartment() == null && department != null) {
            user.setDepartment(department);
            changed = true;
        }
        if (user.getPosition() == null && position != null) {
            user.setPosition(position);
            changed = true;
        }

        if (changed) {
            userRepository.save(user);
        }
    }
}
