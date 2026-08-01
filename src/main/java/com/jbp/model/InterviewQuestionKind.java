package com.jbp.model;

/**
 * The three groups interview questions are organised into.
 *
 * <p><strong>Declaration order is display order</strong> — design 21 fixes it as technical →
 * behavioural → role-specific at every width, and requires the DOM to match. Ordering by this enum
 * rather than by whatever order the model happened to answer in is what makes that hold without the
 * client sorting anything.
 *
 * <p>An enum rather than free text for the same reason as {@link MatchFactorKind}: the client needs a
 * stable key to switch on, and the label is a separate concern that can be reworded without breaking
 * it.
 */
public enum InterviewQuestionKind {

    TECHNICAL("Technical"),
    BEHAVIOURAL("Behavioural"),
    ROLE_SPECIFIC("Role-specific");

    private final String label;

    InterviewQuestionKind(String label) {
        this.label = label;
    }

    /** The group overline design 21 draws. Rendered upper-case by the client, not stored that way. */
    public String getLabel() {
        return label;
    }
}
