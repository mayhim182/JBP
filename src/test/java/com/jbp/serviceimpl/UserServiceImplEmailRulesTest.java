package com.jbp.serviceimpl;

import com.jbp.dto.UserUpdateRequest;
import com.jbp.exception.ConflictException;
import com.jbp.model.Role;
import com.jbp.model.RoleName;
import com.jbp.model.User;
import com.jbp.repository.RoleRepository;
import com.jbp.repository.UserRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.util.UserEmailGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 11.7 — `PUT /api/users/{id}` must not be a way around the password gate.
 */
class UserServiceImplEmailRulesTest {

    private static final Long CALLER_ID = 7L;
    private static final Long SOMEONE_ELSE_ID = 42L;

    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final RoleRepository roleRepository = Mockito.mock(RoleRepository.class);
    private final PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
    private final UserEmailGuard userEmailGuard = Mockito.mock(UserEmailGuard.class);
    private final CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);

    private final UserServiceImpl userService = new UserServiceImpl(
            userRepository, roleRepository, passwordEncoder, userEmailGuard, currentUserProvider);

    @BeforeEach
    void callerIsUserSeven() {
        Mockito.when(currentUserProvider.getCurrentUserId()).thenReturn(CALLER_ID);
        Mockito.when(userRepository.save(Mockito.any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void refusesToChangeTheCallersOwnEmail() {
        givenUser(CALLER_ID, "amara@example.com");

        assertThatThrownBy(() -> userService.updateUser(CALLER_ID, request("Amara", "new@example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/api/users/me/email");

        Mockito.verify(userRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void letsAnAdministratorChangeSomebodyElsesEmail() {
        User target = givenUser(SOMEONE_ELSE_ID, "old@example.com");

        userService.updateUser(SOMEONE_ELSE_ID, request("Bem Adeyemi", "new@example.com"));

        assertThat(target.getEmail()).isEqualTo("new@example.com");
        Mockito.verify(userEmailGuard).ensureAvailable("new@example.com");
    }

    @Test
    void refusesWhenSomebodyElseAlreadyHoldsTheNewAddress() {
        givenUser(SOMEONE_ELSE_ID, "old@example.com");
        Mockito.doThrow(new ConflictException("Email already exists: taken@example.com"))
                .when(userEmailGuard).ensureAvailable("taken@example.com");

        assertThatThrownBy(() -> userService.updateUser(SOMEONE_ELSE_ID, request("Bem", "taken@example.com")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updatesTheNameWithoutTouchingTheEmailRules() {
        User caller = givenUser(CALLER_ID, "amara@example.com");

        userService.updateUser(CALLER_ID, request("Amara O.", "amara@example.com"));

        assertThat(caller.getName()).isEqualTo("Amara O.");
        // Unchanged address → the guard is irrelevant and the self-service rule must not fire,
        // otherwise a candidate could never rename themselves.
        Mockito.verify(userEmailGuard, Mockito.never()).ensureAvailable(Mockito.anyString());
    }

    @Test
    void treatsACaseOnlyDifferenceAsUnchangedSoRenamingStillWorks() {
        givenUser(CALLER_ID, "amara@example.com");

        userService.updateUser(CALLER_ID, request("Amara O.", "Amara@Example.com"));

        Mockito.verify(userEmailGuard, Mockito.never()).ensureAvailable(Mockito.anyString());
    }

    private User givenUser(Long id, String email) {
        Role candidate = Role.builder().id(1L).name(RoleName.ROLE_CANDIDATE).build();
        User user = User.builder()
                .id(id)
                .name("Existing Name")
                .email(email)
                .password("$2a$10$storedhash")
                .roles(Set.of(candidate))
                .enabled(true)
                .build();
        Mockito.when(userRepository.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    private UserUpdateRequest request(String name, String email) {
        return UserUpdateRequest.builder().name(name).email(email).build();
    }
}
