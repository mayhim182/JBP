package com.jbp.service;

import com.jbp.dto.UserRequest;
import com.jbp.dto.UserUpdateRequest;
import com.jbp.dto.UserResponse;

import java.util.List;

public interface UserService {

    UserResponse createUser(UserRequest request);

    UserResponse getUserById(Long id);

    List<UserResponse> getAllUsers();

    UserResponse updateUser(Long id, UserUpdateRequest request);

    void deleteUser(Long id);
}
