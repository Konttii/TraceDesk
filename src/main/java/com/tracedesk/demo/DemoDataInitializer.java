package com.tracedesk.demo;

import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Component
public class DemoDataInitializer  implements CommandLineRunner{

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String demoPassword;

    public DemoDataInitializer(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.demo.password:password}") String demoPassword
    ){
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(String... args){
        createIfAbsent("irina", "Ирина (Следователь)", Role.INVESTIGATOR);
        createIfAbsent("pavel", "Павел (Следователь)", Role.INVESTIGATOR);
        createIfAbsent("oleg", "Олег (Оператор поддержки)", Role.SUPPORT);
        createIfAbsent("anna", "Анна (Оператор поддержки)", Role.SUPPORT);
    }

    private void createIfAbsent(String username, String fullName, Role role){
       if (userRepository.findByUsername(username).isEmpty()){
           User user = new User(
                   username,
                   fullName,
                   passwordEncoder.encode(demoPassword),
                   role
           );
           userRepository.save(user);
       }
    }
}
