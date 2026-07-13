package com.jbp.model;

public enum RoleName {
    ROLE_CANDIDATE,
    ROLE_RECRUITER,
    ROLE_ADMIN;

    // Resolves a role that may be assigned via the API (self-signup or admin-created).
    // Only CANDIDATE and RECRUITER are allowed here; ADMIN is created only via the seeder.
    public static RoleName fromAssignable(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Role is required (CANDIDATE or RECRUITER)");
        }
        return switch (value.trim().toUpperCase()) {
            case "CANDIDATE" -> ROLE_CANDIDATE;
            case "RECRUITER" -> ROLE_RECRUITER;
            default -> throw new IllegalArgumentException(
                    "Invalid role: " + value + ". Allowed values: CANDIDATE, RECRUITER");
        };
    }
}
