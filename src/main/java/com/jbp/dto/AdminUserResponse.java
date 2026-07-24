package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;

/**
 * User view for the admin console — includes the enabled flag (unlike the public UserResponse).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminUserResponse {

    private Long id;
    private String name;
    private String email;
    private Set<String> roles;
    private boolean enabled;
}
