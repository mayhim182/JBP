package com.jbp.model;

/**
 * Trust state of a company / employer.
 * PENDING  - newly created, not yet reviewed by an admin.
 * VERIFIED - approved; earns the "Verified Employer" badge and may publish jobs.
 * REJECTED - reviewed and turned down.
 */
public enum VerificationStatus {
    PENDING,
    VERIFIED,
    REJECTED
}
