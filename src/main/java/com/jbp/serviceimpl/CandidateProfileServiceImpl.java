package com.jbp.serviceimpl;

import com.jbp.dto.CandidateProfileRequest;
import com.jbp.dto.CandidateProfileResponse;
import com.jbp.dto.EducationDto;
import com.jbp.dto.ExperienceDto;
import com.jbp.dto.ProjectDto;
import com.jbp.dto.ResumeUploadResponse;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.model.CandidateProfile;
import com.jbp.model.CandidateProject;
import com.jbp.model.Education;
import com.jbp.model.Experience;
import com.jbp.model.User;
import com.jbp.repository.CandidateProfileRepository;
import com.jbp.repository.UserRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.FileStorageService;
import com.jbp.service.ResumeParser;
import com.jbp.event.EmbeddingRefreshPublisher;
import com.jbp.util.AnswerDraftEligibility;
import com.jbp.util.ProfileCompletenessCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CandidateProfileServiceImpl implements CandidateProfileService {

    private static final String RESUME_SUBDIRECTORY = "resumes";
    private static final Set<String> ALLOWED_RESUME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final CandidateProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final FileStorageService fileStorageService;
    private final ResumeParser resumeParser;
    private final ProfileCompletenessCalculator completenessCalculator;
    private final EmbeddingRefreshPublisher embeddingRefreshPublisher;

    @Override
    @Transactional
    public CandidateProfileResponse getMyProfile() {
        return toResponse(getOrCreateProfile(currentUserProvider.getCurrentUserId()));
    }

    @Override
    @Transactional
    public CandidateProfileResponse updateMyProfile(CandidateProfileRequest request) {
        CandidateProfile profile = getOrCreateProfile(currentUserProvider.getCurrentUserId());
        applyRequestToProfile(profile, request);
        CandidateProfile saved = profileRepository.save(profile);
        log.info("Candidate profile updated for user {}", saved.getUser().getId());
        // Announce the change only; whether it needs an embedding call is decided after this commits,
        // on another thread, so a slow provider cannot hold up the response.
        embeddingRefreshPublisher.candidateProfileChanged(saved.getId());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public ResumeUploadResponse uploadMyResume(MultipartFile file) {
        validateResume(file);
        CandidateProfile profile = getOrCreateProfile(currentUserProvider.getCurrentUserId());

        // Replace any previously uploaded resume.
        fileStorageService.delete(profile.getResumeKey());
        String storageKey = fileStorageService.store(file, RESUME_SUBDIRECTORY);
        profile.setResumeKey(storageKey);
        profile.setResumeFileName(file.getOriginalFilename());
        profile.setResumeContentType(file.getContentType());
        profileRepository.save(profile);
        log.info("Resume stored for user {} with key {}", profile.getUser().getId(), storageKey);

        ResumeParser.ParsedResume parsed = parseQuietly(file);
        return ResumeUploadResponse.builder()
                .resumeFileName(file.getOriginalFilename())
                .contentType(file.getContentType())
                .suggestedEmail(parsed.email())
                .suggestedPhone(parsed.phone())
                .suggestedSkills(parsed.skills())
                .suggestedHeadline(parsed.headline())
                .suggestedLocation(parsed.location())
                .suggestedSeniority(parsed.seniority())
                .suggestedExperiences(parsed.experiences())
                .suggestedEducations(parsed.educations())
                .suggestedProjects(parsed.projects())
                // Ordered set: links arrive deduplicated, and the profile stores them as a Set.
                .suggestedLinks(new LinkedHashSet<>(parsed.links()))
                .build();
    }

    @Override
    @Transactional
    public void deleteMyResume() {
        CandidateProfile profile = getOrCreateProfile(currentUserProvider.getCurrentUserId());
        fileStorageService.delete(profile.getResumeKey());
        profile.setResumeKey(null);
        profile.setResumeFileName(null);
        profile.setResumeContentType(null);
        profileRepository.save(profile);
        log.info("Resume removed for user {}", profile.getUser().getId());
    }

    @Override
    @Transactional
    public CandidateProfile getProfileEntityForCandidate(Long candidateId) {
        return getOrCreateProfile(candidateId);
    }

    @Override
    public Optional<CandidateProfile> findProfileForCandidate(Long candidateId) {
        return profileRepository.findByUserId(candidateId);
    }

    private CandidateProfile getOrCreateProfile(Long candidateId) {
        return profileRepository.findByUserId(candidateId)
                .orElseGet(() -> createEmptyProfile(candidateId));
    }

    private CandidateProfile createEmptyProfile(Long candidateId) {
        User user = userRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + candidateId));
        CandidateProfile profile = CandidateProfile.builder().user(user).build();
        return profileRepository.save(profile);
    }

    private void validateResume(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Resume file is required");
        }
        if (!ALLOWED_RESUME_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("Only PDF or DOCX resumes are allowed");
        }
    }

    private ResumeParser.ParsedResume parseQuietly(MultipartFile file) {
        try {
            return resumeParser.parse(file.getBytes(), file.getContentType());
        } catch (Exception e) {
            log.warn("Resume parsing failed; returning no suggestions: {}", e.getMessage());
            return ResumeParser.ParsedResume.empty();
        }
    }

    private void applyRequestToProfile(CandidateProfile profile, CandidateProfileRequest request) {
        profile.setHeadline(request.getHeadline());
        profile.setLocation(request.getLocation());
        profile.setPhone(request.getPhone());
        profile.setSeniority(request.getSeniority());

        replaceAll(profile.getSkills(), request.getSkills());
        replaceAll(profile.getLinks(), request.getLinks());

        List<Experience> experiences = profile.getExperiences();
        experiences.clear();
        if (request.getExperiences() != null) {
            request.getExperiences().forEach(dto -> experiences.add(toExperience(dto)));
        }

        List<Education> educations = profile.getEducations();
        educations.clear();
        if (request.getEducations() != null) {
            request.getEducations().forEach(dto -> educations.add(toEducation(dto)));
        }

        List<CandidateProject> projects = profile.getProjects();
        projects.clear();
        if (request.getProjects() != null) {
            request.getProjects().forEach(dto -> projects.add(toProject(dto)));
        }
    }

    private void replaceAll(Set<String> target, Set<String> source) {
        target.clear();
        if (source != null) {
            target.addAll(source);
        }
    }

    private Experience toExperience(ExperienceDto dto) {
        return Experience.builder()
                .title(dto.getTitle())
                .company(dto.getCompany())
                .startDate(dto.getStartDate())
                .endDate(dto.getEndDate())
                .description(dto.getDescription())
                .build();
    }

    private Education toEducation(EducationDto dto) {
        return Education.builder()
                .institution(dto.getInstitution())
                .degree(dto.getDegree())
                .fieldOfStudy(dto.getFieldOfStudy())
                .startYear(dto.getStartYear())
                .endYear(dto.getEndYear())
                .build();
    }

    private CandidateProject toProject(ProjectDto dto) {
        return CandidateProject.builder()
                .name(dto.getName())
                .description(dto.getDescription())
                .link(dto.getLink())
                .build();
    }

    private CandidateProfileResponse toResponse(CandidateProfile profile) {
        return CandidateProfileResponse.builder()
                .id(profile.getId())
                .headline(profile.getHeadline())
                .location(profile.getLocation())
                .phone(profile.getPhone())
                .seniority(profile.getSeniority())
                .skills(new HashSet<>(profile.getSkills()))
                .links(new HashSet<>(profile.getLinks()))
                .experiences(profile.getExperiences().stream().map(this::toExperienceDto).toList())
                .educations(profile.getEducations().stream().map(this::toEducationDto).toList())
                .projects(profile.getProjects().stream().map(this::toProjectDto).toList())
                .hasResume(hasText(profile.getResumeKey()))
                .resumeFileName(profile.getResumeFileName())
                .completenessPercent(completenessCalculator.calculate(profile))
                .canDraftAnswers(AnswerDraftEligibility.canGroundADraftedAnswer(profile))
                .build();
    }

    private ExperienceDto toExperienceDto(Experience experience) {
        return ExperienceDto.builder()
                .title(experience.getTitle())
                .company(experience.getCompany())
                .startDate(experience.getStartDate())
                .endDate(experience.getEndDate())
                .description(experience.getDescription())
                .build();
    }

    private EducationDto toEducationDto(Education education) {
        return EducationDto.builder()
                .institution(education.getInstitution())
                .degree(education.getDegree())
                .fieldOfStudy(education.getFieldOfStudy())
                .startYear(education.getStartYear())
                .endYear(education.getEndYear())
                .build();
    }

    private ProjectDto toProjectDto(CandidateProject project) {
        return ProjectDto.builder()
                .name(project.getName())
                .description(project.getDescription())
                .link(project.getLink())
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
