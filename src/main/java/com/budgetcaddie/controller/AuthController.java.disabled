package com.budgetcaddie.controller;

import com.budgetcaddie.model.User;
import com.budgetcaddie.payload.JwtResponse;
import com.budgetcaddie.payload.LoginRequest;
import com.budgetcaddie.repository.UserRepository;
import com.budgetcaddie.security.JwtUtil;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * Login endpoint allowing username or email with password.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        String loginInput = loginRequest.getUsername(); // username or email
        String password = loginRequest.getPassword();

        Optional<User> userOptional = userRepository.findAll()
                .stream()
                .filter(u -> u.getUsername().equalsIgnoreCase(loginInput)
                        || u.getEmail().equalsIgnoreCase(loginInput))
                .findFirst();

        if (userOptional.isEmpty()) {
            logger.warn("Login failed: user not found for '{}'", loginInput);
            return ResponseEntity.badRequest()
                    .body("Error: User not found with username or email: " + loginInput);
        }

        User user = userOptional.get();

        if (!passwordEncoder.matches(password, user.getPassword())) {
            logger.warn("Login failed: incorrect password for user '{}'", loginInput);
            return ResponseEntity.badRequest()
                    .body("Error: Incorrect password");
        }

        String jwt = jwtUtil.generateToken(user.getUsername());

        return ResponseEntity.ok(new JwtResponse(jwt));
    }

    /**
     * Registration endpoint to create a new user account.
     */
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@Valid @RequestBody User user) {

        boolean usernameExists = userRepository.findAll()
                .stream()
                .anyMatch(u -> u.getUsername().equalsIgnoreCase(user.getUsername()));

        if (usernameExists) {
            logger.warn("Registration failed: username '{}' already taken", user.getUsername());
            return ResponseEntity.badRequest()
                    .body("Error: Username is already taken");
        }

        boolean emailExists = userRepository.findAll()
                .stream()
                .anyMatch(u -> u.getEmail().equalsIgnoreCase(user.getEmail()));

        if (emailExists) {
            logger.warn("Registration failed: email '{}' already in use", user.getEmail());
            return ResponseEntity.badRequest()
                    .body("Error: Email is already in use");
        }

        user.setPassword(passwordEncoder.encode(user.getPassword()));

        User savedUser = userRepository.save(user);

        logger.info("User registered successfully: '{}'", savedUser.getUsername());

        return ResponseEntity.ok(savedUser);
    }
}

