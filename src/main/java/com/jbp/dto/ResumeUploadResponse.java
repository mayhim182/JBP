package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;

/**
 * Result of uploading a resume: the stored file's metadata plus deterministic
 * parsing SUGGESTIONS. Suggestions are for the candidate to review and apply via
 * the profile update; nothing is written to the profile automatically.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResumeUploadResponse {

    private String resumeFileName;
    private String contentType;

    private String suggestedEmail;
    private String suggestedPhone;
    private Set<String> suggestedSkills;
}
