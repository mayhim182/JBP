package com.jbp.model;

/**
 * Stages of a job application. Active progression is
 * APPLIED -> VIEWED -> SHORTLISTED -> INTERVIEW -> OFFER.
 * REJECTED and CLOSED are terminal and reachable from any active stage.
 */
public enum ApplicationStatus {
    APPLIED,
    VIEWED,
    SHORTLISTED,
    INTERVIEW,
    OFFER,
    REJECTED,
    CLOSED
}
