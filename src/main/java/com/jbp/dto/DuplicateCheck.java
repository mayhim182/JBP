package com.jbp.dto;

import java.util.List;

/**
 * The answer to "does this posting resemble anything else this company has posted?".
 *
 * <p><strong>Why {@code assessable} exists when the UI draws both cases identically.</strong> A job's
 * vector is written after its submission commits, on another thread, so a check run immediately after
 * a submit can arrive before the vector does. Without this flag that race is invisible: "not looked at
 * yet" and "looked at, found nothing" both serialise as an empty list, and the client has no way to
 * know a retry would help. It is deliberately not drawn — design 29 C renders silence either way,
 * because a recruiter cannot act on the difference — but the client can retry once on it, which is
 * what closes the window.
 *
 * <p>The same distinction Story 14.5 draws between "nobody looked" and "checked and clean", kept
 * below the surface rather than on it.
 */
public record DuplicateCheck(boolean assessable, List<DuplicateJob> duplicates) {

    /** No vector for this posting yet, so no comparison was possible. */
    public static DuplicateCheck notAssessable() {
        return new DuplicateCheck(false, List.of());
    }

    public static DuplicateCheck of(List<DuplicateJob> duplicates) {
        return new DuplicateCheck(true, duplicates);
    }

    /** Never null, so callers can stream it without a guard. */
    @Override
    public List<DuplicateJob> duplicates() {
        return duplicates == null ? List.of() : duplicates;
    }
}
