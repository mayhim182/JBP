package com.jbp.serviceimpl;

import com.jbp.dto.DraftedRejectionReason;
import com.jbp.exception.ConflictException;
import com.jbp.exception.RateLimitExceededException;
import com.jbp.model.Application;
import com.jbp.model.ApplicationStatus;
import com.jbp.model.CandidateProfile;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.User;
import com.jbp.repository.ApplicationRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.RejectionReasonDrafter;
import com.jbp.util.PerUserCallBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story 14.4 — what the drafter is allowed to be asked for, and what it is allowed to be told.
 *
 * <p>The two tests that matter most are the ones about absence: the acceptance criteria forbid the
 * recruiter's private notes and rating from reaching the model, and an absence is exactly the kind of
 * guarantee that rots silently when someone later "enriches the prompt with more context".
 */
class RejectionDraftServiceImplTest {

    private static final long RECRUITER_ID = 7L;
    private static final long SOMEONE_ELSE_ID = 8L;
    private static final long APPLICATION_ID = 42L;

    private ApplicationRepository applicationRepository;
    private CurrentUserProvider currentUserProvider;
    private CandidateProfileService candidateProfileService;
    private CapturingDrafter drafter;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        candidateProfileService = mock(CandidateProfileService.class);
        drafter = new CapturingDrafter();
        when(currentUserProvider.getCurrentUserId()).thenReturn(RECRUITER_ID);
    }

    /**
     * AC: "Never receives the recruiter's private notes or rating."
     *
     * <p>Asserted against the record's shape rather than against one rendered prompt, because that is
     * where the guarantee actually lives. A test that only inspected the prompt would keep passing the
     * day someone adds a {@code recruiterNotes} component and forgets one caller.
     */
    @Test
    void theBriefHasNowhereToPutPrivateNotesOrTheRating() {
        List<String> components = Arrays.stream(
                        RejectionReasonDrafter.RejectionBrief.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components).containsExactly("job", "unmetRequirements");
    }

    /** The behavioural half: an application carrying both still sends neither. */
    @Test
    void sendsNeitherThePrivateNotesNorTheRatingEvenWhenTheApplicationHasThem() {
        Application application = applicationOn(job("Kafka", "Go"), skills("go"));
        application.setRecruiterNotes("Weak on streaming, rambled in the screen. Do not progress.");
        application.setRating(2);
        givenApplication(application);

        service().draftRejectionReason(APPLICATION_ID);

        assertThat(drafter.brief).isNotNull();
        assertThat(drafter.brief.job().getTitle()).isEqualTo("Streaming Engineer");
        assertThat(drafter.brief.unmetRequirements()).containsExactly("Kafka");
    }

    /**
     * AC: output must name the gap. It can only do that if the gap reaches it by name, which is the
     * half this test can prove offline — a weak match arrives as the specific requirements that were
     * not evidenced, not as a score and not as a verdict.
     */
    @Test
    void namesTheRequirementsAWeakApplicationDidNotEvidence() {
        givenApplication(applicationOn(job("Kafka", "Terraform", "Go"), skills("go")));

        service().draftRejectionReason(APPLICATION_ID);

        assertThat(drafter.brief.unmetRequirements()).containsExactlyInAnyOrder("Kafka", "Terraform");
    }

    /**
     * Mirrors {@code RuleBasedMatchScorer.skillsFactor}, which compares lower-cased. If these two ever
     * disagree, the draft names a gap the score already credited — and the candidate cannot check it.
     */
    @Test
    void matchesRequirementsCaseInsensitivelyButKeepsTheJobsOwnCasing() {
        givenApplication(applicationOn(job("Kafka", "Go"), skills("GO", "kotlin")));

        service().draftRejectionReason(APPLICATION_ID);

        assertThat(drafter.brief.unmetRequirements()).containsExactly("Kafka");
    }

    /** A strong candidate can still be turned down; the prompt has a rule for an empty gap. */
    @Test
    void reportsNoUnmetRequirementsWhenTheProfileCoversThemAll() {
        givenApplication(applicationOn(job("Kafka", "Go"), skills("kafka", "go")));

        service().draftRejectionReason(APPLICATION_ID);

        assertThat(drafter.brief.unmetRequirements()).isEmpty();
    }

    @Test
    void treatsAMissingProfileAsEvidencingNothing() {
        givenApplication(applicationOn(job("Kafka"), null));

        service().draftRejectionReason(APPLICATION_ID);

        assertThat(drafter.brief.unmetRequirements()).containsExactly("Kafka");
    }

    @Test
    void refusesAnApplicationOnSomebodyElsesJob() {
        when(currentUserProvider.getCurrentUserId()).thenReturn(SOMEONE_ELSE_ID);
        givenApplication(applicationOn(job("Kafka"), skills("go")));

        assertThatThrownBy(() -> service().draftRejectionReason(APPLICATION_ID))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(drafter.brief).isNull();
    }

    /** Nothing to compose for: {@code REJECTED} is terminal and the move controls are already gone. */
    @Test
    void refusesAnApplicationThatHasAlreadyBeenDecided() {
        Application application = applicationOn(job("Kafka"), skills("go"));
        application.setStatus(ApplicationStatus.REJECTED);
        givenApplication(application);

        assertThatThrownBy(() -> service().draftRejectionReason(APPLICATION_ID))
                .isInstanceOf(ConflictException.class);
        assertThat(drafter.brief).isNull();
    }

    @Test
    void refusesOnceTheCeilingIsReached() {
        givenApplication(applicationOn(job("Kafka"), skills("go")));
        RejectionDraftServiceImpl service = serviceAllowing(1);

        service.draftRejectionReason(APPLICATION_ID);

        assertThatThrownBy(() -> service.draftRejectionReason(APPLICATION_ID))
                .isInstanceOf(RateLimitExceededException.class);
    }

    private RejectionDraftServiceImpl service() {
        return serviceAllowing(100);
    }

    private RejectionDraftServiceImpl serviceAllowing(int callsPerMinute) {
        return new RejectionDraftServiceImpl(
                applicationRepository,
                currentUserProvider,
                candidateProfileService,
                drafter,
                new PerUserCallBudget(callsPerMinute, Duration.ofMinutes(1), 100, Clock.systemUTC()));
    }

    private void givenApplication(Application application) {
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
    }

    private Application applicationOn(Job job, Set<String> candidateSkills) {
        User candidate = User.builder().id(99L).build();
        when(candidateProfileService.findProfileForCandidate(anyLong())).thenReturn(
                candidateSkills == null
                        ? Optional.empty()
                        : Optional.of(CandidateProfile.builder().skills(candidateSkills).build()));

        return Application.builder()
                .id(APPLICATION_ID)
                .job(job)
                .candidate(candidate)
                .status(ApplicationStatus.SHORTLISTED)
                .build();
    }

    private Job job(String... requiredSkills) {
        User owner = User.builder().id(RECRUITER_ID).build();
        Company company = Company.builder().id(3L).owner(owner).build();
        return Job.builder()
                .id(11L)
                .title("Streaming Engineer")
                .description("Runs the event pipeline.")
                .company(company)
                .skills(new LinkedHashSet<>(List.of(requiredSkills)))
                .build();
    }

    private Set<String> skills(String... values) {
        return new HashSet<>(List.of(values));
    }

    /** Records the brief instead of calling a model, so the tests can assert on what was sent. */
    private static final class CapturingDrafter implements RejectionReasonDrafter {

        private RejectionBrief brief;

        @Override
        public DraftedRejectionReason draft(RejectionBrief brief) {
            this.brief = brief;
            return DraftedRejectionReason.builder()
                    .reason("Thank you for applying. This role runs on Kafka day to day.")
                    .build();
        }
    }
}
