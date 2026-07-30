package com.jbp.util;

import com.jbp.dto.JobQualityFinding;
import com.jbp.model.Job;
import com.jbp.model.JobQualityField;
import com.jbp.model.QualityFindingSource;
import com.jbp.model.QualitySeverity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The quality checks that need no model at all.
 *
 * <p>Separate from {@code JobQualityChecker} rather than a branch inside it, because "the rules
 * always run" is a promise better kept by structure than by an if-statement: this class has no AI
 * dependency to fail, so an outage cannot skip it. It is also why the panel still works with
 * {@code app.ai.enabled=false} — nothing here is reachable from the AI path.
 *
 * <p>Every rule answers a question about a *fact* — a field that is absent, too short, or thin.
 * Judgements about wording belong to the model, which is the one thing counting characters cannot do.
 */
@Component
public class JobQualityRules {

    /**
     * Below this a description cannot cover scope, impact and team. Taken from the designs' own
     * advice line ("Aim for 600+"), so the number the recruiter is told matches the one that
     * triggered the finding.
     */
    private static final int MINIMUM_USEFUL_DESCRIPTION_LENGTH = 600;

    /** Fewer than this and matching has too little to work with; the designs suggest 5–8. */
    private static final int MINIMUM_USEFUL_SKILL_COUNT = 5;

    /**
     * Checks a job against every rule, in the order the panel reads best: the fields a recruiter
     * would fix first come first.
     */
    public List<JobQualityFinding> check(Job job) {
        List<JobQualityFinding> findings = new ArrayList<>();
        addSalaryFinding(job, findings);
        addDescriptionFinding(job, findings);
        addSeniorityFinding(job, findings);
        addSkillsFindings(job, findings);
        return findings;
    }

    private void addSalaryFinding(Job job, List<JobQualityFinding> findings) {
        if (job.getSalaryMin() == null || job.getSalaryMax() == null) {
            findings.add(finding(QualitySeverity.HIGH, JobQualityField.SALARY,
                    "No salary range set.",
                    "Add a min and max — ranges attract more applicants."));
        }
    }

    private void addDescriptionFinding(Job job, List<JobQualityFinding> findings) {
        int length = lengthOf(job.getDescription());
        if (length == 0) {
            findings.add(finding(QualitySeverity.HIGH, JobQualityField.DESCRIPTION,
                    "No description written.",
                    "Describe the scope, the impact and the team."));
            return;
        }
        if (length < MINIMUM_USEFUL_DESCRIPTION_LENGTH) {
            findings.add(finding(QualitySeverity.HIGH, JobQualityField.DESCRIPTION,
                    "Only " + length + " characters — thin for this role.",
                    "Aim for " + MINIMUM_USEFUL_DESCRIPTION_LENGTH
                            + "+, covering scope, impact and team."));
        }
    }

    /**
     * The backlog calls this "seniority/experience mismatch". A true mismatch check needs a
     * years-of-experience field, which no job carries — so this reports the case that is actually
     * knowable: no seniority at all, which leaves matching and filtering nothing to rank on.
     */
    private void addSeniorityFinding(Job job, List<JobQualityFinding> findings) {
        if (job.getSeniority() == null) {
            findings.add(finding(QualitySeverity.MEDIUM, JobQualityField.SENIORITY,
                    "No seniority set.",
                    "Pick a level — candidates filter by it and matching ranks on it."));
        }
    }

    private void addSkillsFindings(Job job, List<JobQualityFinding> findings) {
        int skillCount = job.getSkills() == null ? 0 : job.getSkills().size();
        if (skillCount == 0) {
            findings.add(finding(QualitySeverity.HIGH, JobQualityField.SKILLS,
                    "No skills listed.",
                    "Add the skills the role needs — matching depends on them."));
            return;
        }
        if (skillCount < MINIMUM_USEFUL_SKILL_COUNT) {
            findings.add(finding(QualitySeverity.LOW, JobQualityField.SKILLS,
                    "Only " + skillCount + " skill" + (skillCount == 1 ? "" : "s") + " listed.",
                    MINIMUM_USEFUL_SKILL_COUNT + "–8 skills sharpens candidate matching."));
        }
    }

    private JobQualityFinding finding(QualitySeverity severity,
                                      JobQualityField field,
                                      String message,
                                      String suggestion) {
        return JobQualityFinding.builder()
                .severity(severity)
                .field(field)
                .message(message)
                .suggestion(suggestion)
                .source(QualityFindingSource.RULE)
                .build();
    }

    private int lengthOf(String text) {
        return text == null ? 0 : text.trim().length();
    }
}
