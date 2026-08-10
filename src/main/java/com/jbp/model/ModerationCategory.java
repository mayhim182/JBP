package com.jbp.model;

/**
 * The kind of problem one moderation flag describes.
 *
 * <p>A closed set, and the acceptance criteria's set exactly. The assistant reports the categories
 * an admin already moderates against; it does not get to invent new grounds for concern, because a
 * category nobody has agreed on is a category nobody knows how to act on. Anything the model returns
 * outside this set is dropped — see {@code AiJobModerationAssistant}.
 */
public enum ModerationCategory {

    /** Fake or non-existent role, impersonated employer, advance-fee patterns. */
    SCAM,

    /** Recruitment into a downline rather than employment. */
    MLM,

    /** Any requirement that the applicant pay for training, equipment or placement. */
    PAY_TO_APPLY,

    /** Wording that excludes on age, gender, race, nationality, religion or disability. */
    DISCRIMINATORY_LANGUAGE,

    /** Earnings or progression no honest employer could promise. */
    UNREALISTIC_CLAIMS
}
