package com.jbp.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * The three lines design 24 draws, and the three outcomes the caller has to tell apart.
 *
 * <p>Distinguished by the fields themselves rather than by a status flag that could disagree with
 * them:
 * <ul>
 *   <li><strong>Any line null</strong> — the model was never reached, or its reply was unusable. Only
 *       {@link #unavailable()} produces this, because {@code @NotNull} means a reply omitting a line
 *       is discarded in favour of that same fallback. Design 24 B2.</li>
 *   <li><strong>All three blank</strong> — the model was reached and <strong>declined</strong>: the
 *       profile did not carry enough to ground a read, and the prompt tells it to return empty
 *       strings rather than invent one. Design 24 B4.</li>
 *   <li><strong>All three present</strong> — the read. Design 24 A.</li>
 * </ul>
 *
 * <p>A reply with some lines filled and others blank is none of these. It is treated as unavailable
 * by {@code AiApplicantSummarizer}, on the same "accepted whole or discarded whole" rule the shared
 * pipeline already applies: half a read is a shape the panel cannot draw and a reader cannot trust.
 *
 * <p>{@link Serializable} because instances are held in a Caffeine cache — see
 * {@code CachingApplicantSummarizer}.
 *
 * <p><strong>This is also the response body</strong>, rather than being copied into a near-identical
 * wire DTO. The three lines are the whole of what the endpoint returns, so a second class would carry
 * the same three fields and one more mapping to keep in step. The validation annotations only run on
 * a parsed model reply and cost nothing on the way out.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicantSummary implements Serializable {

    /** Roughly two drawn lines each. Long enough for a real sentence, short enough to stay a read. */
    private static final int MAX_LINE_LENGTH = 300;

    @NotNull
    @Size(max = MAX_LINE_LENGTH)
    private String strongestFit;

    @NotNull
    @Size(max = MAX_LINE_LENGTH)
    private String mainGap;

    @NotNull
    @Size(max = MAX_LINE_LENGTH)
    private String worthProbing;

    /** The fallback: the one value with null lines, and so the one that means "not reached". */
    public static ApplicantSummary unavailable() {
        return ApplicantSummary.builder().build();
    }

    /**
     * A decline the summarizer can reach without asking the model — a profile with nothing in it at
     * all. Identical in shape to the decline the model itself returns, because it means the same
     * thing to the recruiter and must render as the same state.
     */
    public static ApplicantSummary declined() {
        return ApplicantSummary.builder().strongestFit("").mainGap("").worthProbing("").build();
    }

    /**
     * Whether the model could not be used at all. Not {@code isX()} — Jackson reads that as a bean
     * property and would add a phantom boolean to the JSON, the same trap
     * {@link SuggestedScreeningQuestions#hasContent()} documents.
     */
    public boolean wasUnavailable() {
        return strongestFit == null || mainGap == null || worthProbing == null;
    }

    /** Whether the model answered but found nothing in the profile to ground a read in. */
    public boolean wasDeclined() {
        return !wasUnavailable()
                && strongestFit.isBlank() && mainGap.isBlank() && worthProbing.isBlank();
    }

    /**
     * Whether all three lines are present and worth drawing.
     *
     * <p>Not {@code isComplete()}: this object is also the response body, and Jackson would read an
     * {@code isX()} method as a bean property and put a phantom {@code "complete": true} on the wire.
     */
    public boolean hasAllThreeLines() {
        return !wasUnavailable()
                && !strongestFit.isBlank() && !mainGap.isBlank() && !worthProbing.isBlank();
    }
}
