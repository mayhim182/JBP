package com.jbp.controller;

import com.jbp.dto.UserRequest;
import com.jbp.dto.UserResponse;
import com.jbp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserRequest request) {
        log.info("Creating user with email={}", request.getEmail());
        UserResponse response = userService.createUser(request);
        log.debug("User created successfully with id={}", response.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        log.debug("Fetching user by id={}", id);
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        log.debug("Fetching all users");
        List<UserResponse> users = userService.getAllUsers();
        log.debug("Found {} users", users.size());
        return ResponseEntity.ok(users);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UserRequest request) {
        log.info("Updating user id={} with email={}", id, request.getEmail());
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        log.info("Deleting user id={}", id);
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
