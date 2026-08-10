package com.jbp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One thing the moderation assistant flagged, and the single line explaining why.
 *
 * <p>Stored rather than recomputed: the queue sorts by risk on every load, and re-asking the model to
 * rank a backlog would spend a request per job per page view. Mapped as an {@link Embeddable} on the
 * job's own collection table for the same reason {@link ScreeningQuestion} is — a flag has no identity
 * away from the job it describes, and it dies with it.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModerationFlag {

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 40)
    private ModerationCategory category;

    /**
     * One sentence naming what was found, in the assistant's words.
     *
     * <p>Sized for a sentence, not a paragraph. An admin reads these stacked under a row, and the
     * value of a rationale is that it can be taken in at a glance beside the posting it describes.
     */
    @Column(name = "rationale", length = 300)
    private String rationale;
}
