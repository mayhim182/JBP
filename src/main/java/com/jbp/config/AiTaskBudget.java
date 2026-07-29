package com.jbp.config;

/**
 * How much input a single AI task may send to the model.
 *
 * <p>Exists as a bean rather than a {@code @Value} on each task so the {@code app.ai.*} key is
 * read in {@code AiClientConfig} and nowhere else — the same rule Story 11.1 set for the URL,
 * key and model name. Epics 11 to 14 add roughly ten tasks; without this each one would repeat
 * the property key, and changing the budget would mean editing ten files.
 *
 * @param maxInputTokens upper bound on the prompt size a task may send
 */
public record AiTaskBudget(int maxInputTokens) {
}
