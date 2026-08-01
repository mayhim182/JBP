package com.jbp.dto;

import com.jbp.model.ScreeningQuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Suggested screening questions, each with the answer type the model thinks it wants.
 *
 * <p>A named wrapper rather than a bare list because {@code AbstractStructuredAiTask.responseType()}
 * takes a {@code Class}, which cannot express a generic element type — and a named object also gives
 * the model a clearer shape to aim at than a top-level array.
 *
 * <p><b>The type is asked for, not derived.</b> The editor used to read short / long / yes-no out of
 * the question's opening words, which is why the prompt carried phrasing rules the model had to obey
 * for the right control to appear. Both are gone: the model states the type, the recruiter can
 * override it before accepting, and the wording is free to be whatever asks the question best.
 *
 * <p>Its own element type rather than {@link ScreeningQuestionDto}: this one is model output under
 * validation, so it carries a length cap and a required type that would be wrong on the shape a
 * recruiter saves. A suggestion is never untyped — that is what keeps the editor's "pick one" prompt
 * out of the suggestions panel.
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
    @Valid
    @Size(max = 10)
    private List<SuggestedScreeningQuestion> questions;

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

    /**
     * One suggestion: what to ask, and what to ask it with.
     *
     * <p>{@code answerType} is required here even though it is nullable everywhere else. A suggestion
     * with no type would reach the panel as a blank segmented control the recruiter has to fill in
     * before accepting, which is worse than no suggestion — and an unparseable type is exactly the
     * kind of malformed reply the shared pipeline exists to discard.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SuggestedScreeningQuestion {

        @Size(max = 300)
        private String question;

        @NotNull
        private ScreeningQuestionType answerType;
    }
}
