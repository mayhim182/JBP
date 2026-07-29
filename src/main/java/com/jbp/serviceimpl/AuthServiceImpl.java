package com.jbp.serviceimpl;

import com.jbp.dto.AuthResponse;
import com.jbp.dto.EmailChangeRequest;
import com.jbp.dto.LoginRequest;
import com.jbp.dto.RegisterRequest;
import com.jbp.dto.UserResponse;
import com.jbp.model.Role;
import com.jbp.model.RoleName;
import com.jbp.model.User;
import com.jbp.repository.RoleRepository;
import com.jbp.repository.UserRepository;
import com.jbp.security.JwtService;
import com.jbp.security.UserPrincipal;
import com.jbp.service.AuthService;
import com.jbp.util.UserEmailGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String BEARER_TOKEN_TYPE = "Bearer";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserEmailGuard userEmailGuard;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        userEmailGuard.ensureAvailable(request.getEmail());

        RoleName roleName = RoleName.fromAssignable(request.getRole());
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role not found: " + roleName));

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .roles(Set.of(role))
                .enabled(true)
                .build();

        User saved = userRepository.save(user);
        log.info("User registered with id={}, email={}", saved.getId(), saved.getEmail());
        return buildAuthResponse(saved);
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        log.debug("Login attempt for email={}", request.getEmail());

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        log.info("User logged in with id={}, email={}", user.getId(), user.getEmail());
        return buildAuthResponse(user);
    }

    @Override
    public UserResponse getCurrentUser() {
        return toUserResponse(findAuthenticatedUser());
    }

    @Override
    @Transactional
    public AuthResponse changeEmail(EmailChangeRequest request) {
        User user = findAuthenticatedUser();

        /*
         * Password first, and a failure returns immediately. Checking availability first would let
         * anyone holding a session probe which addresses are registered without ever proving who
         * they are; after a correct password the caller has already proved that.
         */
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            log.warn("Email change rejected for user id={} — current password did not match", user.getId());
            throw new BadCredentialsException("Current password is incorrect");
        }

        String newEmail = request.getNewEmail().trim();
        if (!newEmail.equalsIgnoreCase(user.getEmail())) {
            userEmailGuard.ensureAvailable(newEmail);
            user.setEmail(newEmail);
            userRepository.save(user);
            log.info("Email changed for user id={}", user.getId());
        }

        /*
         * A fresh token even when the address did not actually change, so the caller has one
         * response shape to handle. When it did change the reissue is mandatory: JwtService puts the
         * email in the token's subject and JwtAuthenticationFilter resolves the user by it, so the
         * token the caller sent with this very request no longer names anybody.
         */
        return buildAuthResponse(user);
    }

    /**
     * Takes the {@link User} rather than re-reading it by email, which also removes the redundant
     * lookup register and login used to perform after they already had the entity in hand.
     */
    private AuthResponse buildAuthResponse(User user) {
        return AuthResponse.builder()
                .accessToken(jwtService.generateToken(new UserPrincipal(user)))
                .tokenType(BEARER_TOKEN_TYPE)
                .expiresIn(jwtService.getExpirationMs())
                .user(toUserResponse(user))
                .build();
    }

    private User findAuthenticatedUser() {
        String email = getAuthenticatedPrincipal().getEmail();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists: " + email));
    }

    private UserPrincipal getAuthenticatedPrincipal() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal;
        }
        throw new IllegalStateException("User is not authenticated");
    }

    private UserResponse toUserResponse(User user) {
        Set<String> roles = user.getRoles().stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toSet());

        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .roles(roles)
                .build();
    }
}
