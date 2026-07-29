package com.jbp.serviceimpl;

import com.jbp.dto.AuthResponse;
import com.jbp.dto.EmailChangeRequest;
import com.jbp.exception.ConflictException;
import com.jbp.model.Role;
import com.jbp.model.RoleName;
import com.jbp.model.User;
import com.jbp.repository.RoleRepository;
import com.jbp.repository.UserRepository;
import com.jbp.security.JwtService;
import com.jbp.security.UserPrincipal;
import com.jbp.util.UserEmailGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 11.7 — the password gate on changing your own sign-in email.
 */
class AuthServiceImplEmailChangeTest {

    private static final String CURRENT_EMAIL = "amara@example.com";
    private static final String NEW_EMAIL = "amara.okafor@example.com";
    private static final String CORRECT_PASSWORD = "correct-horse";
    private static final String STORED_HASH = "$2a$10$storedhash";

    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final RoleRepository roleRepository = Mockito.mock(RoleRepository.class);
    private final PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
    private final JwtService jwtService = Mockito.mock(JwtService.class);
    private final AuthenticationManager authenticationManager = Mockito.mock(AuthenticationManager.class);
    private final UserEmailGuard userEmailGuard = Mockito.mock(UserEmailGuard.class);

    private final AuthServiceImpl authService = new AuthServiceImpl(
            userRepository, roleRepository, passwordEncoder, jwtService, authenticationManager, userEmailGuard);

    private User signedInUser;

    @BeforeEach
    void signIn() {
        Role candidate = Role.builder().id(1L).name(RoleName.ROLE_CANDIDATE).build();
        signedInUser = User.builder()
                .id(7L)
                .name("Amara Okafor")
                .email(CURRENT_EMAIL)
                .password(STORED_HASH)
                .roles(Set.of(candidate))
                .enabled(true)
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new UserPrincipal(signedInUser), null, Set.of()));

        Mockito.when(userRepository.findByEmail(CURRENT_EMAIL)).thenReturn(Optional.of(signedInUser));
        Mockito.when(userRepository.save(Mockito.any(User.class))).thenAnswer(call -> call.getArgument(0));
        Mockito.when(jwtService.generateToken(Mockito.any())).thenReturn("fresh.jwt.token");
        Mockito.when(jwtService.getExpirationMs()).thenReturn(86_400_000L);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void changesTheEmailAndReturnsAFreshTokenWhenThePasswordIsCorrect() {
        Mockito.when(passwordEncoder.matches(CORRECT_PASSWORD, STORED_HASH)).thenReturn(true);

        AuthResponse response = authService.changeEmail(request(NEW_EMAIL, CORRECT_PASSWORD));

        assertThat(signedInUser.getEmail()).isEqualTo(NEW_EMAIL);
        assertThat(response.getUser().getEmail()).isEqualTo(NEW_EMAIL);
        assertThat(response.getAccessToken())
                .as("the old token names the previous address in its subject, so it stops resolving")
                .isEqualTo("fresh.jwt.token");
        Mockito.verify(userRepository).save(signedInUser);
    }

    @Test
    void rejectsAWrongPasswordAndLeavesTheEmailAlone() {
        Mockito.when(passwordEncoder.matches("wrong", STORED_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.changeEmail(request(NEW_EMAIL, "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(signedInUser.getEmail()).isEqualTo(CURRENT_EMAIL);
        Mockito.verify(userRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void neverRevealsWhetherTheNewAddressWasTakenWhenThePasswordIsWrong() {
        Mockito.when(passwordEncoder.matches("wrong", STORED_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.changeEmail(request(NEW_EMAIL, "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        // The guard is what would answer 409 "already in use" — it must not even be consulted,
        // or a session with no password could enumerate registered addresses by status code.
        Mockito.verify(userEmailGuard, Mockito.never()).ensureAvailable(Mockito.anyString());
    }

    @Test
    void reportsAConflictWhenAnotherAccountHoldsTheNewAddress() {
        Mockito.when(passwordEncoder.matches(CORRECT_PASSWORD, STORED_HASH)).thenReturn(true);
        Mockito.doThrow(new ConflictException("Email already exists: " + NEW_EMAIL))
                .when(userEmailGuard).ensureAvailable(NEW_EMAIL);

        assertThatThrownBy(() -> authService.changeEmail(request(NEW_EMAIL, CORRECT_PASSWORD)))
                .isInstanceOf(ConflictException.class);

        assertThat(signedInUser.getEmail()).isEqualTo(CURRENT_EMAIL);
    }

    @Test
    void issuesATokenWithoutWritingWhenTheAddressIsUnchanged() {
        Mockito.when(passwordEncoder.matches(CORRECT_PASSWORD, STORED_HASH)).thenReturn(true);

        AuthResponse response = authService.changeEmail(request(CURRENT_EMAIL, CORRECT_PASSWORD));

        assertThat(response.getAccessToken()).isEqualTo("fresh.jwt.token");
        Mockito.verify(userRepository, Mockito.never()).save(Mockito.any());
        Mockito.verify(userEmailGuard, Mockito.never()).ensureAvailable(Mockito.anyString());
    }

    @Test
    void treatsACaseOnlyDifferenceAsUnchanged() {
        Mockito.when(passwordEncoder.matches(CORRECT_PASSWORD, STORED_HASH)).thenReturn(true);

        authService.changeEmail(request(CURRENT_EMAIL.toUpperCase(), CORRECT_PASSWORD));

        Mockito.verify(userEmailGuard, Mockito.never()).ensureAvailable(Mockito.anyString());
        Mockito.verify(userRepository, Mockito.never()).save(Mockito.any());
    }

    private EmailChangeRequest request(String newEmail, String currentPassword) {
        return EmailChangeRequest.builder().newEmail(newEmail).currentPassword(currentPassword).build();
    }
}
