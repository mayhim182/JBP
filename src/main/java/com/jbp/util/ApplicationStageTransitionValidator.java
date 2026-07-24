package com.jbp.util;

import com.jbp.exception.ConflictException;
import com.jbp.model.ApplicationStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Enforces legal application stage transitions. Active stages may only move forward;
 * REJECTED and CLOSED are terminal and reachable from any active stage.
 */
@Component
public class ApplicationStageTransitionValidator {

    private static final List<ApplicationStatus> ACTIVE_ORDER = List.of(
            ApplicationStatus.APPLIED,
            ApplicationStatus.VIEWED,
            ApplicationStatus.SHORTLISTED,
            ApplicationStatus.INTERVIEW,
            ApplicationStatus.OFFER);

    public void validateTransition(ApplicationStatus from, ApplicationStatus to) {
        if (!isAllowed(from, to)) {
            throw new ConflictException("Illegal application stage transition: " + from + " -> " + to);
        }
    }

    private boolean isAllowed(ApplicationStatus from, ApplicationStatus to) {
        if (from == to || isTerminal(from)) {
            return false;
        }
        if (to == ApplicationStatus.REJECTED || to == ApplicationStatus.CLOSED) {
            return true;
        }
        return ACTIVE_ORDER.indexOf(to) > ACTIVE_ORDER.indexOf(from);
    }

    private boolean isTerminal(ApplicationStatus status) {
        return status == ApplicationStatus.REJECTED || status == ApplicationStatus.CLOSED;
    }
}
