package com.jbp.controller;

import com.jbp.dto.AuthResponse;
import com.jbp.dto.EmailChangeRequest;
import com.jbp.dto.UserRequest;
import com.jbp.dto.UserUpdateRequest;
import com.jbp.dto.UserResponse;
import com.jbp.service.AuthService;
import com.jbp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    /** Owns credential verification and token issuance, which the email change needs both of. */
    private final AuthService authService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserRequest request) {
        log.info("Creating user with email={}", request.getEmail());
        UserResponse response = userService.createUser(request);
        log.debug("User created successfully with id={}", response.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isOwner(#id)")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        log.debug("Fetching user by id={}", id);
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        log.debug("Fetching all users");
        List<UserResponse> users = userService.getAllUsers();
        log.debug("Found {} users", users.size());
        return ResponseEntity.ok(users);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isOwner(#id)")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UserUpdateRequest request) {
        log.info("Updating user id={} with email={}", id, request.getEmail());
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    /**
     * Changes the signed-in user's own email, proving identity with their current password, and
     * returns a fresh token they must store in place of the old one.
     *
     * <p>Mounted here rather than under {@code /api/auth} deliberately: {@code SecurityConfig}
     * declares {@code /api/auth/**} as {@code permitAll}, so an email-change route there would be
     * reachable without a token, and would stay that way silently if anyone later reordered those
     * matchers. Under {@code /api/users} it inherits {@code anyRequest().authenticated()} and is
     * closed by default. No {@code @PreAuthorize} is needed because the operation reads the caller
     * from the security context and can only ever act on them — there is no id to get wrong.
     */
    @PutMapping("/me/email")
    public ResponseEntity<AuthResponse> changeMyEmail(@Valid @RequestBody EmailChangeRequest request) {
        // The new address is deliberately absent from this line: it is a credential in flight.
        log.info("Email change requested by the authenticated user");
        return ResponseEntity.ok(authService.changeEmail(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        log.info("Deleting user id={}", id);
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
