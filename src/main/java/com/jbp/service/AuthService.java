package com.jbp.service;

import com.jbp.dto.AuthResponse;
import com.jbp.dto.LoginRequest;
import com.jbp.dto.RegisterRequest;
import com.jbp.dto.UserResponse;

public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    UserResponse getCurrentUser();
}
