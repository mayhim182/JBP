package com.jbp.serviceimpl;

import com.jbp.dto.AdminUserResponse;
import com.jbp.exception.ConflictException;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.model.RoleName;
import com.jbp.model.User;
import com.jbp.repository.UserRepository;
import com.jbp.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserServiceImpl implements AdminUserService {

    private final UserRepository userRepository;

    @Override
    public List<AdminUserResponse> getUsers(String query) {
        List<User> users = (query == null || query.isBlank())
                ? userRepository.findAll()
                : userRepository.findByEmailContainingIgnoreCaseOrNameContainingIgnoreCase(query, query);
        return users.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public AdminUserResponse banUser(Long userId) {
        User user = findUserOrThrow(userId);
        if (hasAdminRole(user)) {
            throw new ConflictException("Cannot ban an admin user");
        }
        user.setEnabled(false);
        userRepository.save(user);
        log.info("User {} banned by admin", userId);
        return toResponse(user);
    }

    @Override
    @Transactional
    public AdminUserResponse reactivateUser(Long userId) {
        User user = findUserOrThrow(userId);
        user.setEnabled(true);
        userRepository.save(user);
        log.info("User {} reactivated by admin", userId);
        return toResponse(user);
    }

    private boolean hasAdminRole(User user) {
        return user.getRoles().stream().anyMatch(role -> role.getName() == RoleName.ROLE_ADMIN);
    }

    private User findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
    }

    private AdminUserResponse toResponse(User user) {
        return AdminUserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .roles(user.getRoles().stream().map(role -> role.getName().name()).collect(Collectors.toSet()))
                .enabled(user.isEnabled())
                .build();
    }
}
