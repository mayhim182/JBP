package com.jbp.serviceimpl;

import com.jbp.dto.UserRequest;
import com.jbp.dto.UserUpdateRequest;
import com.jbp.dto.UserResponse;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.model.Role;
import com.jbp.model.RoleName;
import com.jbp.model.User;
import com.jbp.repository.RoleRepository;
import com.jbp.repository.UserRepository;
import com.jbp.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserResponse createUser(UserRequest request) {
        log.debug("Checking if email already exists: {}", request.getEmail());
        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("User creation failed — email already exists: {}", request.getEmail());
            throw new IllegalArgumentException("Email already exists: " + request.getEmail());
        }

        Role userRole = roleRepository.findByName(RoleName.ROLE_USER)
                .orElseThrow(() -> new IllegalStateException("Default role ROLE_USER not found"));

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .roles(Set.of(userRole))
                .enabled(true)
                .build();

        User saved = userRepository.save(user);
        log.info("User created with id={}, email={}", saved.getId(), saved.getEmail());
        return toResponse(saved);
    }

    @Override
    public UserResponse getUserById(Long id) {
        log.debug("Looking up user by id={}", id);
        return toResponse(findUserOrThrow(id));
    }

    @Override
    public List<UserResponse> getAllUsers() {
        log.debug("Fetching all users from repository");
        List<UserResponse> users = userRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
        log.debug("Retrieved {} users", users.size());
        return users;
    }

    @Override
    @Transactional
    public UserResponse updateUser(Long id, UserUpdateRequest request) {
        User user = findUserOrThrow(id);
        log.debug("Updating user id={}: name='{}' -> '{}', email='{}' -> '{}'",
                id, user.getName(), request.getName(), user.getEmail(), request.getEmail());
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        User updated = userRepository.save(user);
        log.info("User updated with id={}", updated.getId());
        return toResponse(updated);
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User user = findUserOrThrow(id);
        userRepository.delete(user);
        log.info("User deleted with id={}, email={}", id, user.getEmail());
    }

    private User findUserOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("User not found with id={}", id);
                    return new ResourceNotFoundException("User not found with id: " + id);
                });
    }

    private UserResponse toResponse(User user) {
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
