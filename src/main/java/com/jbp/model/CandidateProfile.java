package com.jbp.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "candidate_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandidateProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The candidate who owns this profile. One profile per candidate.
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    private String headline;

    private String location;

    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "candidate_profile_skills", joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "skill")
    private Set<String> skills = new HashSet<>();

    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "candidate_profile_links", joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "link")
    private Set<String> links = new HashSet<>();

    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "candidate_profile_experiences", joinColumns = @JoinColumn(name = "profile_id"))
    private List<Experience> experiences = new ArrayList<>();

    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "candidate_profile_educations", joinColumns = @JoinColumn(name = "profile_id"))
    private List<Education> educations = new ArrayList<>();

    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "candidate_profile_projects", joinColumns = @JoinColumn(name = "profile_id"))
    private List<CandidateProject> projects = new ArrayList<>();

    // Resume metadata; the file itself lives in storage (local now, S3 later).
    private String resumeKey;

    private String resumeFileName;

    private String resumeContentType;
}
