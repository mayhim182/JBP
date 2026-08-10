package com.jbp.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 5000)
    private String description;

    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "job_skills", joinColumns = @JoinColumn(name = "job_id"))
    @Column(name = "skill")
    private Set<String> skills = new HashSet<>();

    private String location;

    @Builder.Default
    @Column(nullable = false)
    private boolean remote = false;

    @Enumerated(EnumType.STRING)
    private JobType type;

    @Enumerated(EnumType.STRING)
    private SeniorityLevel seniority;

    private Integer salaryMin;

    private Integer salaryMax;

    // The element carries its own column mappings — see ScreeningQuestion.
    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "job_screening_questions", joinColumns = @JoinColumn(name = "job_id"))
    private List<ScreeningQuestion> screeningQuestions = new ArrayList<>();

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.DRAFT;

    /**
     * When this job entered moderation, or null if it never has.
     *
     * <p>Stamped on submission rather than on creation, because the review queue's question is "how
     * long has this been waiting" — a job can sit in draft for weeks before anyone submits it, and
     * ordering by creation would send the queue's oldest-first view to the wrong row. Null on every
     * job submitted before this field existed, which the queue renders as no date rather than a guess.
     */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    /**
     * What the moderation assistant made of this posting, or null if it has never been assessed.
     *
     * <p>Null is load-bearing and is why this has no default: every job written before the assistant
     * existed, and every job written while the capability is switched off, is genuinely unassessed
     * rather than clean. {@link ModerationRisk#NONE} says the opposite, and the queue draws them
     * differently.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_risk", length = 10)
    private ModerationRisk moderationRisk;

    // Empty whenever the risk is NONE, and also whenever the risk is null — which is why the risk
    // and not this collection is what tells the two apart.
    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "job_moderation_flags", joinColumns = @JoinColumn(name = "job_id"))
    private List<ModerationFlag> moderationFlags = new ArrayList<>();

    // The company this job is posted under. The company's owner is the recruiter,
    // which is how job ownership is resolved.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;
}
