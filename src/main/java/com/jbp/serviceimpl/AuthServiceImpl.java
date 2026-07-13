package com.jbp.serviceimpl;

import com.jbp.dto.AuthResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
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

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("Registration failed — email already exists: {}", request.getEmail());
            throw new IllegalArgumentException("Email already exists: " + request.getEmail());
        }

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

        UserPrincipal principal = new UserPrincipal(saved);
        return buildAuthResponse(principal);
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        log.debug("Login attempt for email={}", request.getEmail());

        AuthenticationManager authManager = authenticationManager;
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword());

        authManager.authenticate(authToken);

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        UserPrincipal principal = new UserPrincipal(user);
        log.info("User logged in with id={}, email={}", user.getId(), user.getEmail());
        return buildAuthResponse(principal);
    }

    @Override
    public UserResponse getCurrentUser() {
        UserPrincipal principal = getAuthenticatedPrincipal();
        User user = userRepository.findByEmail(principal.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return toUserResponse(user);
    }

    private AuthResponse buildAuthResponse(UserPrincipal principal) {
        String token = jwtService.generateToken(principal);
        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationMs())
                .user(toUserResponse(userRepository.findByEmail(principal.getEmail()).orElseThrow()))
                .build();
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
