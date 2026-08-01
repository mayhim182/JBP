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
 * One screening question on a job: the text the candidate is asked, and the control they answer it
 * with.
 *
 * <p>Previously this was a bare {@code String} and the answer control was guessed from the wording.
 * Pairing the two here is what lets the recruiter choose it instead — see {@link
 * ScreeningQuestionType}.
 *
 * <p><strong>{@code answerType} is nullable and stays nullable.</strong> Null means "nobody has
 * chosen one", which is true of every question written before this field existed. There is
 * deliberately no backfill: guessing a type once and storing it would make the guess permanent and
 * indistinguishable from a recruiter's own choice, and the guess is the thing being removed. A job
 * with untyped questions still saves and still publishes; the editor asks for the type when the
 * recruiter next opens it, and the apply form falls back to a single line meanwhile.
 *
 * <p>Mapped into the pre-existing {@code job_screening_questions} table on its pre-existing {@code
 * question} column, so the change adds a column and rewrites no rows.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScreeningQuestion {

    @Column(name = "question", length = 1000)
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(name = "answer_type", length = 20)
    private ScreeningQuestionType answerType;
}
