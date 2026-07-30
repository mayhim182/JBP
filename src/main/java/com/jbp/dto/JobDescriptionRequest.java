package com.jbp.dto;

import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;

/**
 * What the editor sends to have a first-draft description written.
 *
 * <p>Deliberately a subset of {@link JobRequest} rather than a reuse of it: generating needs only
 * the fields that describe the role, and accepting the whole request would invite a caller to
 * believe the description it already holds is being sent or saved. Nothing here is persisted.
 *
 * <p>The company name and description are not accepted from the client. They are read server-side
 * from the authenticated recruiter's own company, so a caller cannot have a draft written against
 * somebody else's employer branding.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobDescriptionRequest {

    @NotBlank(message = "Job title is required")
    private String title;

    private Set<String> skills;

    private String location;

    private boolean remote;

    private JobType type;

    private SeniorityLevel seniority;
}
