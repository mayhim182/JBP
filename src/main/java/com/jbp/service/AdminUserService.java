package com.jbp.service;

import com.jbp.dto.AdminUserResponse;

import java.util.List;

/** Admin user management (Story 9.3). */
public interface AdminUserService {

    /** Lists users; if {@code query} is non-blank, filters by email or name. */
    List<AdminUserResponse> getUsers(String query);

    AdminUserResponse banUser(Long userId);

    AdminUserResponse reactivateUser(Long userId);
}
