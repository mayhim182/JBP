package com.jbp.model;

/**
 * Which part of the posting a finding is about.
 *
 * <p>An enum rather than free text because the editor's "Fix →" scrolls to the field a finding names.
 * A string would make that a guess: a model writing "the description" one time and "Description"
 * the next would break the jump silently, and a typo would be indistinguishable from a field that
 * genuinely has no control.
 *
 * <p>{@link #PHRASING} is the one value that is not a form field. It exists because the designs list
 * it in the field column for wording problems — coded language, vague responsibilities — which live
 * in the description but are not about the description being absent or short. The editor points its
 * jump at the description control and shows the word "Phrasing", so the recruiter reads why they are
 * being sent there.
 */
public enum JobQualityField {

    TITLE,

    DESCRIPTION,

    /** Wording within the description, rather than the description itself. */
    PHRASING,

    SKILLS,

    SALARY,

    SENIORITY,

    SCREENING
}
