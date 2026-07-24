package com.jbp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A candidate's answer to a job's screening question, captured at apply time
 * (question text stored alongside the answer so it survives later job edits).
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScreeningAnswer {

    @Column(length = 1000)
    private String question;

    @Column(length = 2000)
    private String answer;
}
