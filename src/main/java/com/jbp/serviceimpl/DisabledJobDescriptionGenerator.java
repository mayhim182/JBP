package com.jbp.serviceimpl;

import com.jbp.dto.GeneratedJobDescription;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.JobDescriptionGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * What serves when the job-description capability is switched off.
 *
 * <p>Raises the same failure a real outage does, so the editor renders design 17b's drawn unavailable
 * state — the "Generate with AI" pill visible and disabled with one quiet line — rather than a second
 * treatment invented for this switch. That difference from Story 14.1, where the whole section is
 * absent, is deliberate: a recruiter was promised a control and would hunt for it, whereas a candidate
 * has no prior expectation of a prep section.
 *
 * <p>Paired by condition with {@link AiJobDescriptionGenerator}, exactly as {@code AiClientConfig}
 * pairs its real and disabled chat clients, so precisely one bean exists at any time and callers never
 * face an ambiguous dependency.
 */
@Service
@ConditionalOnProperty(name = "app.ai.features.job-description", havingValue = "false")
public class DisabledJobDescriptionGenerator implements JobDescriptionGenerator {

    @Override
    public GeneratedJobDescription generate(JobDescriptionBrief brief) {
        throw new LlmUnavailableException("Job description drafting is switched off", false);
    }
}
