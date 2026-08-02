package com.jbp.config;

/**
 * Which AI features are switched on, as the client is told about them.
 *
 * <p><strong>Per capability rather than one boolean</strong>, decided with the designer on
 * 2026-08-01. "We turned interview prep off because the questions were poor" is a far likelier
 * operational event than "we turned all AI off", and one flag cannot express it. It would also
 * couple Epic 12's disabled-control pattern to Epic 14's absent-section pattern — two deliberately
 * different treatments that have to be independently switchable.
 *
 * <p><strong>The master switch refines downward, never upward.</strong> Each field is
 * {@code app.ai.enabled && app.ai.features.<name>}, so no capability can be on while AI as a whole
 * is off. Behaviour matches that even without this record: with AI off the injected client is
 * {@link com.jbp.serviceimpl.DisabledChatClient}, which raises the same failure a real outage would.
 * This exists so the <em>reported</em> configuration cannot disagree with the actual one.
 *
 * <p>A bean rather than a {@code @Value} on each consumer, so every {@code app.ai.*} key is still
 * read in {@link AiClientConfig} and nowhere else — the rule Story 11.1 set and
 * {@link AiTaskBudget} already follows.
 */
public record AiCapabilities(boolean interviewPrep,
                             boolean matchExplanation,
                             boolean jobDescription,
                             boolean screeningAnswerAssist,
                             boolean applicantSummary) {

    /** Every capability off — what the client is told when AI is switched off entirely. */
    public static AiCapabilities none() {
        return new AiCapabilities(false, false, false, false, false);
    }
}
