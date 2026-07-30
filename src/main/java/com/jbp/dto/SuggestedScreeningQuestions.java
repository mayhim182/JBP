package com.jbp.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Suggested screening questions, as plain text.
 *
 * <p>A named wrapper rather than a bare {@code List<String>} because
 * {@code AbstractStructuredAiTask.responseType()} takes a {@code Class}, which cannot express a
 * generic element type — and a named object also gives the model a clearer shape to aim at than a
 * top-level array.
 *
 * <p><b>No answer type is carried.</b> The editor derives short / long / yes-no from the wording
 * with {@code screeningInputKind}, the same function that decides which control the candidate
 * actually gets. Returning a type here would let the tag the recruiter sees disagree with the
 * control the candidate is given, because {@code Job.screeningQuestions} is a list of strings with
 * nowhere to store one. Deriving keeps the tag true by construction; the prompt's job is to phrase
 * each question so that derivation lands where it should.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuggestedScreeningQuestions {

    /**
     * The prompt asks for 3 to 5. This bound is a sanity check, not that rule: rejecting a reply of
     * two good questions would trade a usable answer for none, while a reply of forty has clearly
     * gone wrong and is worth discarding whole.
     */
    @Size(max = 10)
    private List<@Size(max = 300) String> questions;

    /** The value returned when the model could not be used. Named for what it means to the caller. */
    public static SuggestedScreeningQuestions noSuggestionsAvailable() {
        return SuggestedScreeningQuestions.builder().questions(List.of()).build();
    }

    /**
     * Whether anything is worth showing. Not {@code isEmpty()} — Jackson reads an {@code isX()}
     * method as a bean property and would add a phantom boolean to the JSON the editor receives.
     */
    public boolean hasContent() {
        return questions != null && !questions.isEmpty();
    }
}
