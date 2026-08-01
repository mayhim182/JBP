package com.jbp.repository;

import com.jbp.model.Application;
import com.jbp.model.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    boolean existsByCandidateIdAndJobId(Long candidateId, Long jobId);

    List<Application> findByCandidateId(Long candidateId);

    List<Application> findByJobId(Long jobId);

    long countByStatus(ApplicationStatus status);

    /**
     * How many candidates gave a real answer to each question text on one job.
     *
     * <p>Aggregated in the database rather than by streaming {@link #findByJobId}: the caller wants a
     * handful of numbers, and a popular job has thousands of applications it has no other reason to
     * load. Questions nobody answered are simply absent from the result — the caller fills the zeros.
     *
     * <p>{@code DISTINCT} counts people, not rows, so a candidate cannot inflate the number. A null
     * question is excluded rather than grouped: the caller keys the result by question text, and a
     * null key would fail the whole request over a row that names no question to begin with.
     */
    @Query("""
            SELECT answer.question AS question, COUNT(DISTINCT application.id) AS answeredCount
            FROM Application application
            JOIN application.screeningAnswers answer
            WHERE application.job.id = :jobId
              AND answer.question IS NOT NULL
              AND answer.answer IS NOT NULL
              AND TRIM(answer.answer) <> ''
            GROUP BY answer.question
            """)
    List<ScreeningAnswerCount> countAnswersPerQuestion(@Param("jobId") Long jobId);

    /** One aggregated row of {@link #countAnswersPerQuestion}. Names must match the query's aliases. */
    interface ScreeningAnswerCount {

        String getQuestion();

        long getAnsweredCount();
    }
}
