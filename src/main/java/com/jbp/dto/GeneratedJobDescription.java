package com.jbp.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * A generated description, kept in four sections rather than one blob so the editor can preview it
 * as the designs draw it and a recruiter can judge each part before inserting any of it.
 *
 * <p>One shape serves three roles — the model's response type, the generator's return value and the
 * API response body — because all three are the same four sections. Translating between identical
 * shapes would be duplication, and a second copy would let the prompt and the API drift apart.
 *
 * <p>The size limits are the only constraints. Every section is optional, because a reply missing
 * one is still worth showing; what they reject is a reply that has clearly run away with itself,
 * which {@code AbstractStructuredAiTask} then discards whole rather than previewing.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneratedJobDescription {

    @Size(max = 2000)
    private String summary;

    @Size(max = 20)
    private List<@Size(max = 500) String> responsibilities;

    @Size(max = 20)
    private List<@Size(max = 500) String> requirements;

    @Size(max = 20)
    private List<@Size(max = 500) String> niceToHave;

    /**
     * The value returned when no draft could be produced. Named for what it means to the caller
     * rather than for being empty: {@code AbstractStructuredAiTask} requires a fallback, but there
     * is no non-AI way to write a description, so this is a signal rather than a usable result.
     */
    public static GeneratedJobDescription noDraftAvailable() {
        return GeneratedJobDescription.builder()
                .summary(null)
                .responsibilities(List.of())
                .requirements(List.of())
                .niceToHave(List.of())
                .build();
    }

    /**
     * Whether this carries anything worth previewing.
     *
     * <p>Not named {@code isEmpty}/{@code isUsable} on purpose — Jackson reads an {@code isX()}
     * method as a bean property, which would add a phantom boolean field to the JSON the editor
     * receives. {@code hasContent()} is invisible to serialisation.
     */
    public boolean hasContent() {
        return isPresent(summary)
                || isPopulated(responsibilities)
                || isPopulated(requirements)
                || isPopulated(niceToHave);
    }

    private boolean isPresent(String section) {
        return section != null && !section.isBlank();
    }

    private boolean isPopulated(List<String> section) {
        return section != null && !section.isEmpty();
    }
}
