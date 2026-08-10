package com.jbp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.JobModerationAssistant;
import com.jbp.serviceimpl.AiJobModerationAssistant;
import com.jbp.serviceimpl.DisabledJobModerationAssistant;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles Story 14.5's assistant. The same shape as {@link RejectionDraftConfig}, and for the same
 * reason: the capability flag decides which implementation exists, so the switch is resolved once at
 * startup rather than re-tested at each call site.
 *
 * <p><strong>No ceiling bean</strong>, unlike the drafter and the summariser. Those are triggered by a
 * person who can press the button again; this runs once per submitted job, from a listener with no
 * user attached, so there is no retry loop for a per-user budget to bound.
 */
@Slf4j
@Configuration
public class ModerationAssistConfig {

    @Bean
    public JobModerationAssistant jobModerationAssistant(AiCapabilities aiCapabilities,
                                                         ChatCompletionClient chatCompletionClient,
                                                         ObjectMapper objectMapper,
                                                         Validator validator,
                                                         AiTaskBudget aiTaskBudget) {
        if (!aiCapabilities.moderationAssist()) {
            log.info("Moderation assist is off — submitted jobs are queued unassessed and unsorted");
            return new DisabledJobModerationAssistant();
        }
        return new AiJobModerationAssistant(chatCompletionClient, objectMapper, validator, aiTaskBudget);
    }
}
