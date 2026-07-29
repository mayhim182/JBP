package com.jbp.service;

import com.jbp.dto.AuthResponse;
import com.jbp.dto.EmailChangeRequest;
import com.jbp.dto.LoginRequest;
import com.jbp.dto.RegisterRequest;
import com.jbp.dto.UserResponse;

public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    UserResponse getCurrentUser();

    /**
     * Changes the signed-in user's own email after verifying their current password.
     *
     * <p>Belongs here rather than on {@code UserService} because it does two things only this
     * service does: it verifies a credential, and it issues a token. The returned
     * {@link AuthResponse} carries a **fresh** token that the caller must store — the previous one
     * names the old address in its subject, which is how the request filter resolves the user, so it
     * stops working the instant the address changes.
     *
     * @throws org.springframework.security.authentication.BadCredentialsException if the password is wrong
     * @throws com.jbp.exception.ConflictException if another account already uses the new address
     */
    AuthResponse changeEmail(EmailChangeRequest request);
}
