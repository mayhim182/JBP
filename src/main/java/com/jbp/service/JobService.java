package com.jbp.service;

import com.jbp.dto.GeneratedJobDescription;
import com.jbp.dto.JobDescriptionRequest;
import com.jbp.dto.JobQualityFinding;
import com.jbp.dto.JobRequest;
import com.jbp.dto.JobResponse;
import com.jbp.dto.ScreeningQuestionAnswerCount;

import java.util.List;

public interface JobService {

    JobResponse createJob(JobRequest request);

    /**
     * Writes a first-draft description for a role the current recruiter is authoring.
     *
     * <p>Lives here because it needs the recruiter's own company, which this service already
     * resolves for {@link #createJob}. The AI itself stays behind {@code JobDescriptionGenerator} —
     * this only supplies the context and returns the draft, and nothing is saved.
     */
    GeneratedJobDescription generateDescription(JobDescriptionRequest request);

    /**
     * The deterministic half of the quality check: instant, and unaffected by whether AI is on.
     *
     * <p>Split from {@link #checkQualityWithAi} so the editor can paint findings immediately rather
     * than waiting on a model — the designs show rules landing first and AI arriving after. Owner-only,
     * and nothing is stored: a check is recomputed on demand.
     */
    List<JobQualityFinding> checkQualityWithRules(Long jobId);

    /**
     * The wording half. Returns an empty list when AI is unavailable rather than failing, because the
     * rules have already given the recruiter something to act on.
     */
    List<JobQualityFinding> checkQualityWithAi(Long jobId);

    JobResponse updateJob(Long id, JobRequest request);

    void deleteJob(Long id);

    JobResponse publishJob(Long id);

    JobResponse closeJob(Long id);

    JobResponse cloneJob(Long id);

    /** Public view: returns the job only if it is PUBLISHED, otherwise 404. */
    JobResponse getPublishedJobById(Long id);

    /** All jobs owned by the current recruiter, in any status. */
    List<JobResponse> getMyJobs();

    /**
     * How many candidates have already answered each of a job's screening questions, so the editor can
     * warn before an edit lands on a question people have replied to.
     *
     * <p>Owner-only, and one entry per question in the job's own order — including the zeros, so the
     * editor can line the numbers up against the questions it is already showing.
     *
     * <p>Matched on the question's text, which is the only handle there is: {@code ScreeningAnswer}
     * snapshots the wording at apply time and a screening question carries no id. So a question whose
     * wording was edited at some earlier point reports the answers given since that edit, not the ones
     * given before it. Accepted knowingly — the alternative is an id on every question and a migration
     * to assign one, and the number here drives a warning rather than a decision.
     */
    List<ScreeningQuestionAnswerCount> getScreeningAnswerCounts(Long jobId);
}
