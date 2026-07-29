package com.jbp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Changing the signed-in user's own email address.
 *
 * <p>Both fields are required, because the whole point of this request is to do the two things
 * together: email is the sign-in credential, so changing it must prove the caller is who they say
 * they are. There is no variant of this operation that omits the password.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailChangeRequest {

    @NotBlank(message = "New email is required")
    @Email(message = "Email must be valid")
    private String newEmail;

    @NotBlank(message = "Your current password is required to change your email")
    private String currentPassword;
}
