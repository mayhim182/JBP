package com.jbp.service;

import com.jbp.dto.JobRiskAssessment;
import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;

import java.util.Optional;
import java.util.Set;

/**
 * Reads a job posting and says how risky it looks, so an admin reviews the dangerous ones first.
 *
 * <p><strong>Advisory, structurally.</strong> Nothing on this path can change a job's status: the
 * assistant returns a judgement and the admin acts on it. That is not a promise made by the prompt —
 * it is a consequence of this interface returning a value rather than performing an action, and of
 * the only caller being a listener that writes two fields and stops.
 *
 * <p>Empty rather than an exception when the model cannot be used, unlike
 * {@link RejectionReasonDrafter}. Nobody is waiting on this: it runs after the recruiter's submit has
 * already returned, so there is no one to show a retry to. An unassessed job is a state the queue
 * already draws.
 */
public interface JobModerationAssistant {

    /**
     * Assesses one posting, or returns empty when the model could not be used or its answer was
     * unusable. Never throws.
     */
    Optional<JobRiskAssessment> assess(JobModerationBrief brief);

    /**
     * The posting as the assistant sees it — and, as with every brief in this layer, what it is
     * structurally incapable of seeing.
     *
     * <p><strong>No company.</strong> Not the name, not the domain, not its verification state or its
     * history of rejected posts. The assistant judges the text in front of it, which is what lets the
     * queue tell an admin the assessment read the post and nothing else. A model that has seen an
     * employer's past reports would be scoring the employer, and no acceptance criterion asks for that.
     *
     * <p><strong>Salary is included</strong>, unlike {@link JobQualityChecker.JobQualityBrief} which
     * withholds it. The reason inverts here: that task was told to ignore missing fields, so a number
     * it had to disregard only invited comment. Unrealistic earnings are a category this task exists to
     * catch, and they are unrecognisable without the figure.
     */
    record JobModerationBrief(String title,
                              String description,
                              Set<String> skills,
                              SeniorityLevel seniority,
                              JobType type,
                              Integer salaryMin,
                              Integer salaryMax) {
    }
}
