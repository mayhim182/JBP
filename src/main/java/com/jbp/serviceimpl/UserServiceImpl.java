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
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.UserService;
import com.jbp.util.UserEmailGuard;
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
    private final UserEmailGuard userEmailGuard;
    private final CurrentUserProvider currentUserProvider;

    @Override
    @Transactional
    public UserResponse createUser(UserRequest request) {
        userEmailGuard.ensureAvailable(request.getEmail());

        // Admin chooses the role (CANDIDATE or RECRUITER); defaults to CANDIDATE if omitted.
        // ADMIN cannot be assigned here — admins are created only via the seeder.
        RoleName roleName = (request.getRole() == null || request.getRole().isBlank())
                ? RoleName.ROLE_CANDIDATE
                : RoleName.fromAssignable(request.getRole());
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
        String requestedEmail = request.getEmail().trim();

        if (!requestedEmail.equalsIgnoreCase(user.getEmail())) {
            rejectChangingYourOwnEmailHere(id);
            userEmailGuard.ensureAvailable(requestedEmail);
            user.setEmail(requestedEmail);
        }

        log.debug("Updating user id={}: name='{}' -> '{}'", id, user.getName(), request.getName());
        user.setName(request.getName());
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        User updated = userRepository.save(user);
        log.info("User updated with id={}", updated.getId());
        return toResponse(updated);
    }

    /**
     * Changing your *own* sign-in email has to prove it is you, and this endpoint takes no password —
     * so it is not the route for it. {@code PUT /api/users/me/email} is, and it also returns the fresh
     * token such a change requires.
     *
     * <p>Comparing the target id to the caller's rather than checking for the admin role is what
     * makes this correct in every case: an administrator editing somebody else legitimately does not
     * know their password, while an administrator editing their own address is gated exactly like
     * anyone else. Without this, a stolen token could change the address it signs in with.
     */
    private void rejectChangingYourOwnEmailHere(Long targetUserId) {
        if (targetUserId.equals(currentUserProvider.getCurrentUserId())) {
            throw new IllegalArgumentException(
                    "Changing your own email requires your current password — use PUT /api/users/me/email");
        }
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
