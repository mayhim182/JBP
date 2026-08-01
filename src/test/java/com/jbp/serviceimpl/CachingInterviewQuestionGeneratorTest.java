package com.jbp.serviceimpl;

import com.jbp.config.InterviewPrepCacheConfig;
import com.jbp.model.InterviewQuestionKind;
import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.InterviewQuestionGenerator;
import com.jbp.service.InterviewQuestionGenerator.InterviewQuestions;
import com.jbp.service.InterviewQuestionGenerator.JobBrief;
import com.jbp.service.InterviewQuestionGenerator.QuestionGroup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.1's acceptance criterion — <strong>one call per job, not one per viewer</strong>.
 *
 * <p>Run through a real Spring context, because {@code @Cacheable} does nothing without the proxy: a
 * unit test that built this class with {@code new} would pass while caching nothing, and the only
 * symptom in production would be a provider bill.
 */
class CachingInterviewQuestionGeneratorTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(CachingTestConfig.class);

    @Test
    void asksTheModelOnceHoweverManyCandidatesOpenTheSameJob() {
        contextRunner.run(context -> {
            InterviewQuestionGenerator generator = context.getBean(InterviewQuestionGenerator.class);
            CountingGenerator delegate = context.getBean(CountingGenerator.class);

            generator.generate(brief("Own the payment ledger."));
            generator.generate(brief("Own the payment ledger."));
            generator.generate(brief("Own the payment ledger."));

            assertThat(delegate.calls)
                    .as("the UI promises \"same questions for every candidate — cached per job\"")
                    .isEqualTo(1);
        });
    }

    @Test
    void generatesAgainOnceTheRecruiterEditsThePosting() {
        contextRunner.run(context -> {
            InterviewQuestionGenerator generator = context.getBean(InterviewQuestionGenerator.class);
            CountingGenerator delegate = context.getBean(CountingGenerator.class);

            generator.generate(brief("Own the payment ledger."));
            generator.generate(brief("Own the payment ledger and the settlement pipeline."));

            assertThat(delegate.calls)
                    .as("keying on the job id would serve the old questions forever after an edit")
                    .isEqualTo(2);
        });
    }

    @Test
    void doesNotCacheAFailureSoADeliberateRetryReachesTheModel() {
        contextRunner.run(context -> {
            InterviewQuestionGenerator generator = context.getBean(InterviewQuestionGenerator.class);
            CountingGenerator delegate = context.getBean(CountingGenerator.class);
            delegate.failing = true;

            assertThat(catchFailure(generator)).isTrue();
            assertThat(catchFailure(generator)).isTrue();

            assertThat(delegate.calls)
                    .as("design 21b's Try again must actually try; the bound on repeat clicking is "
                            + "one retry per viewer, which is client state")
                    .isEqualTo(2);
        });
    }

    private boolean catchFailure(InterviewQuestionGenerator generator) {
        try {
            generator.generate(brief("Own the payment ledger."));
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private JobBrief brief(String description) {
        return new JobBrief("Senior Backend Engineer", description, Set.of("java", "kafka"),
                SeniorityLevel.SENIOR, JobType.FULL_TIME);
    }

    @Configuration
    @EnableCaching
    static class CachingTestConfig {

        @Bean
        @Primary
        CacheManager interviewPrepCacheManager() {
            return new InterviewPrepCacheConfig().interviewPrepCacheManager(100, 24);
        }

        @Bean
        CountingGenerator countingGenerator() {
            return new CountingGenerator();
        }

        @Bean
        @Primary
        InterviewQuestionGenerator interviewQuestionGenerator(CountingGenerator delegate) {
            return new CachingInterviewQuestionGenerator(delegate);
        }
    }

    /** Counts what actually reached the model side of the decorator. */
    static class CountingGenerator implements InterviewQuestionGenerator {

        private int calls;
        private boolean failing;

        @Override
        public InterviewQuestions generate(JobBrief brief) {
            calls++;
            if (failing) {
                throw new IllegalStateException("model unavailable");
            }
            return new InterviewQuestions(List.of(new QuestionGroup(
                    InterviewQuestionKind.TECHNICAL,
                    List.of("One.", "Two.", "Three.", "Four.", "Five."))));
        }
    }
}
