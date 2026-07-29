package com.jbp.util;

import com.jbp.exception.ConflictException;
import com.jbp.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserEmailGuardTest {

    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final UserEmailGuard guard = new UserEmailGuard(userRepository);

    @Test
    void allowsAnAddressNobodyHolds() {
        Mockito.when(userRepository.existsByEmail("free@example.com")).thenReturn(false);

        assertThatCode(() -> guard.ensureAvailable("free@example.com")).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnAddressAlreadyInUse() {
        Mockito.when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> guard.ensureAvailable("taken@example.com"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("taken@example.com");
    }

    @Test
    void reportsConflictSoCallersAnswer409ConsistentlyRatherThanEachChoosingAStatus() {
        Mockito.when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        ConflictException failure = catchConflict();

        assertThat(failure).isNotNull();
    }

    private ConflictException catchConflict() {
        try {
            guard.ensureAvailable("taken@example.com");
            throw new AssertionError("Expected ConflictException");
        } catch (ConflictException expected) {
            return expected;
        }
    }
}
