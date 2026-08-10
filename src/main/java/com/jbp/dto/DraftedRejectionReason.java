package com.jbp.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The rejection the model drafted, and the one distinction the caller has to make: whether there is
 * prose to put in the textarea, or nothing.
 *
 * <p><strong>Two outcomes, not three.</strong> Story 14.3's summary has a third — <em>declined</em>,
 * meaning the model was reached and found nothing to ground a read in — because its input is the
 * candidate's profile, which can genuinely be empty, and a decline is then a stable fact that
 * retrying cannot change. This task's input is the <em>job</em>, which always carries a title and a
 * description, so "nothing to work from" is not a state the data can reach. A blank reply is
 * therefore a failed reply, and design 26 C4's offer to try again stays honest.
 *
 * <p><strong>Not cached</strong>, unlike the summary. A summary is read repeatedly while a recruiter
 * triages; a rejection is drafted once, at the moment of writing it, and design 26 replaces the
 * trigger with the provenance label as soon as it lands. There is no second read to serve, and
 * caching would only make a deliberate re-draft return the prose the recruiter just rejected.
 *
 * <p><strong>This is also the response body.</strong> One field is the whole of what the endpoint
 * returns, so a separate wire DTO would carry the same field and a mapping to keep in step — the same
 * call {@link ApplicantSummary} documents.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DraftedRejectionReason {

    /**
     * Generous for the two or three sentences the acceptance criteria ask for — design 26's own
     * specimen is 311 characters — but far below the column's 2,000, so a model that starts writing a
     * letter is discarded rather than dropped into the recruiter's textarea for them to prune.
     */
    private static final int MAX_REASON_LENGTH = 800;

    @NotNull
    @Size(max = MAX_REASON_LENGTH)
    private String reason;

    /** The fallback: no prose, which design 26 C4 draws. */
    public static DraftedRejectionReason unavailable() {
        return DraftedRejectionReason.builder().build();
    }

    /**
     * Whether there is nothing to offer the recruiter. Blank counts: an empty draft is not a draft,
     * and the panel has one failure state rather than two.
     *
     * <p>Not {@code isX()} — Jackson would read that as a bean property and put a phantom boolean on
     * the wire, the trap {@link ApplicantSummary#wasUnavailable()} documents.
     */
    public boolean wasUnavailable() {
        return reason == null || reason.isBlank();
    }
}
