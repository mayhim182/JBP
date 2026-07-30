package com.jbp.dto;

import com.jbp.model.SeniorityLevel;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;

/**
 * What the editor sends to have screening questions suggested.
 *
 * <p>Three fields only — a question is about the role, not the posting. Salary, location and remote
 * would not change what is worth asking a candidate, so they are not sent; the smaller the prompt,
 * the less there is for the model to wander into.
 *
 * <p>Unlike {@link JobDescriptionRequest} this carries no company context and needs none, which is
 * why suggesting does not require the recruiter to have created a company yet.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScreeningQuestionsRequest {

    @NotBlank(message = "Job title is required")
    private String title;

    private Set<String> skills;

    private SeniorityLevel seniority;
}
