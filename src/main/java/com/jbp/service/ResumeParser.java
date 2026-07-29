package com.jbp.service;

import com.jbp.dto.EducationDto;
import com.jbp.dto.ExperienceDto;
import com.jbp.dto.ProjectDto;
import com.jbp.model.SeniorityLevel;

import java.util.List;
import java.util.Set;

/**
 * Extracts structured suggestions from a resume file. Implementations must degrade gracefully on
 * unparseable input: a resume that cannot be read yields no suggestions rather than an error, so
 * the upload itself always succeeds.
 */
public interface ResumeParser {

    ParsedResume parse(byte[] content, String contentType);

    /**
     * Suggestions extracted from a resume; any field may be null or empty. Nothing here is
     * written to a profile automatically — the candidate reviews and chooses what to apply.
     *
     * <p>Experience, education and project entries reuse the existing profile DTOs so a
     * suggestion needs no translation before the candidate can apply it.
     */
    record ParsedResume(String email,
                        String phone,
                        Set<String> skills,
                        String headline,
                        String location,
                        SeniorityLevel seniority,
                        List<ExperienceDto> experiences,
                        List<EducationDto> educations,
                        List<ProjectDto> projects,
                        List<String> links) {

        public static ParsedResume empty() {
            return ofContactDetails(null, null, Set.of());
        }

        /**
         * A result carrying only what regex and dictionary rules can find, with the richer
         * sections left empty. Keeps the deterministic parser readable now that the record covers
         * everything an LLM can extract too.
         */
        public static ParsedResume ofContactDetails(String email, String phone, Set<String> skills) {
            return new ParsedResume(email, phone, skills, null, null, null,
                    List.of(), List.of(), List.of(), List.of());
        }
    }
}
