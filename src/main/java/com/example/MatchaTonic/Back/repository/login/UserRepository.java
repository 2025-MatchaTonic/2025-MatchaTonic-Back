package com.example.MatchaTonic.Back.repository.login;

import com.example.MatchaTonic.Back.entity.login.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
