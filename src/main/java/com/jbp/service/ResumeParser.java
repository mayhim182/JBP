package com.jbp.service;

import java.util.Set;

/**
 * Extracts structured suggestions from a resume file using deterministic rules
 * (no LLM). Implementations must degrade gracefully on unparseable input.
 */
public interface ResumeParser {

    ParsedResume parse(byte[] content, String contentType);

    /** Suggestions extracted from a resume; any field may be null/empty. */
    record ParsedResume(String email, String phone, Set<String> skills) {
        public static ParsedResume empty() {
            return new ParsedResume(null, null, Set.of());
        }
    }
}
