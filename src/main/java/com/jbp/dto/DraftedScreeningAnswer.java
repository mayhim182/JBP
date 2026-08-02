package com.jbp.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What the model returns for one screening answer, and the three outcomes the caller has to tell
 * apart.
 *
 * <p>They are distinguished by {@code draft} alone rather than by a second flag that could disagree
 * with it:
 * <ul>
 *   <li><strong>null</strong> — the model was never reached, or its reply was unusable. Only
 *       {@link #unavailable()} produces this, because {@code @NotNull} means a reply that omits the
 *       field fails validation and is discarded in favour of that same fallback. Answered 503.</li>
 *   <li><strong>blank</strong> — the model was reached and <em>declined</em>: the profile did not
 *       carry enough to ground an answer, and the prompt tells it to return an empty string rather
 *       than invent one. Answered 422.</li>
 *   <li><strong>anything else</strong> — the draft.</li>
 * </ul>
 *
 * <p>{@code @Size(max = 2000)} is the schema limit on {@code ScreeningAnswer.answer}, enforced here
 * so a draft that overruns is discarded rather than handed to a field that cannot hold it. The prompt
 * asks for far less than this; the bound exists for the reply that ignores it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DraftedScreeningAnswer {

    @NotNull
    @Size(max = 2000)
    private String draft;

    /** The fallback: the one value with a null draft, and so the one that means "not reached". */
    public static DraftedScreeningAnswer unavailable() {
        return DraftedScreeningAnswer.builder().draft(null).build();
    }

    /**
     * Whether the model could not be used at all. Not {@code isX()} — Jackson reads that as a bean
     * property and would add a phantom boolean to the JSON, the same trap
     * {@link SuggestedScreeningQuestions#hasContent()} documents.
     */
    public boolean wasUnavailable() {
        return draft == null;
    }

    /** Whether the model answered but had nothing in the profile to ground the answer in. */
    public boolean wasDeclined() {
        return draft != null && draft.isBlank();
    }
}
