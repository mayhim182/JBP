package com.jbp.service;

import com.jbp.dto.JobQualityFinding;
import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;

import java.util.List;
import java.util.Set;

/**
 * The half of the quality check only a model can do — judgements about wording.
 *
 * <p>Deliberately narrower than "check this job": the deterministic half lives in
 * {@code JobQualityRules} and never comes near this interface. Splitting them is what makes "the
 * rules always run" structural — with AI off, nothing on this path is reached and the panel still
 * has findings to show.
 *
 * <p>Unlike {@link JobDescriptionGenerator} and {@link ScreeningQuestionSuggester}, this one
 * <b>degrades quietly</b>. An empty list is a truthful answer — it means "nothing further to flag" —
 * so an outage costs the recruiter the AI findings and nothing else. The panel is advisory either
 * way, and it never blocks publishing.
 */
public interface JobQualityChecker {

    /**
     * Returns wording problems, or an empty list when the model could not be used.
     *
     * <p>Never throws. The caller has already run the rules and has something to show regardless.
     */
    List<JobQualityFinding> check(JobQualityBrief brief);

    /**
     * The posting as the model sees it. Job content only — no candidate data reaches this task, which
     * is what lets Epic 12 ship independently of the data-retention question governing Epic 11.
     */
    record JobQualityBrief(String title,
                           String description,
                           Set<String> skills,
                           SeniorityLevel seniority,
                           JobType type) {
    }
}
