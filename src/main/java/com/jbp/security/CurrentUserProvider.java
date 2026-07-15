package com.jbp.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for reading the currently authenticated user from the
 * security context, so services don't repeat SecurityContextHolder plumbing.
 */
@Component
public class CurrentUserProvider {

    public UserPrincipal getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new IllegalStateException("No authenticated user in the security context");
        }
        return principal;
    }

    public Long getCurrentUserId() {
        return getCurrentUser().getId();
    }
}
