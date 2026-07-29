package com.jbp.util;

import com.jbp.exception.ConflictException;
import com.jbp.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * Enforces that an email address is not already taken, in one place.
 *
 * <p>Extracted once a fourth caller appeared: registration, admin user creation, admin user update
 * and the self-service email change all need the same rule, and three of them had grown their own
 * copy of it. The rule is not only the check — it is also which status the failure carries, and a
 * duplicated copy is how one caller ends up answering 400 where the others answer 409.
 *
 * <p>A pre-check rather than a replacement for the database constraint. The unique index on
 * {@code users.email} is what actually holds under a race between two concurrent registrations; this
 * exists so the ordinary case gets a clear 409 instead of a constraint violation surfacing as one.
 */
@Component
public class UserEmailGuard {

    private final UserRepository userRepository;

    public UserEmailGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @throws ConflictException if another account already uses this address
     */
    public void ensureAvailable(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already exists: " + email);
        }
    }
}
