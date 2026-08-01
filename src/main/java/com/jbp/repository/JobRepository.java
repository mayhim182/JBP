package com.jbp.repository;

import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {

    // All jobs owned by a recruiter, resolved via job -> company -> owner.
    List<Job> findByCompany_Owner_Id(Long ownerId);

    // Jobs in a given lifecycle status (used by candidate matching + admin moderation queue).
    List<Job> findByStatus(JobStatus status);

    // A bounded page of one status, so candidate matching never loads the whole table.
    Page<Job> findByStatus(JobStatus status, Pageable pageable);

    /**
     * The jobs among {@code ids} that are in the given status — one query for a whole page of
     * scores.
     *
     * <p>An id that is unknown, or known but not published, is simply absent from the result rather
     * than an error. That is the same tolerance the callers already had when every score was its own
     * request and a 404 meant one missing ring, so nothing downstream has to change to keep it.
     */
    List<Job> findByIdInAndStatus(Collection<Long> ids, JobStatus status);

    long countByStatus(JobStatus status);

    /**
     * Searches PUBLISHED jobs. Keyword {@code q} is matched with MySQL FULLTEXT on
     * title and description (indexed separately) plus a LIKE match on skills. All other
     * parameters are optional filters (null = ignored). Results are ordered by a weighted
     * relevance score (title counts double) when a keyword is given, then most-recent-first.
     * Requires the FULLTEXT indexes created by {@code FullTextIndexInitializer}.
     */
    @Query(value = """
            SELECT j.* FROM jobs j
            WHERE j.status = 'PUBLISHED'
              AND (:q IS NULL
                   OR MATCH(j.title) AGAINST (COALESCE(:q, '') IN NATURAL LANGUAGE MODE)
                   OR MATCH(j.description) AGAINST (COALESCE(:q, '') IN NATURAL LANGUAGE MODE)
                   OR EXISTS (SELECT 1 FROM job_skills s
                              WHERE s.job_id = j.id AND LOWER(s.skill) LIKE COALESCE(:qLike, '')))
              AND (:location IS NULL OR LOWER(j.location) LIKE :location)
              AND (:remote IS NULL OR j.remote = :remote)
              AND (:type IS NULL OR j.type = :type)
              AND (:seniority IS NULL OR j.seniority = :seniority)
              AND (:salaryMin IS NULL OR j.salary_max >= :salaryMin)
            ORDER BY
                CASE WHEN :q IS NULL THEN 0
                     ELSE (2 * MATCH(j.title) AGAINST (COALESCE(:q, '') IN NATURAL LANGUAGE MODE)
                           + MATCH(j.description) AGAINST (COALESCE(:q, '') IN NATURAL LANGUAGE MODE))
                END DESC,
                j.id DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM jobs j
            WHERE j.status = 'PUBLISHED'
              AND (:q IS NULL
                   OR MATCH(j.title) AGAINST (COALESCE(:q, '') IN NATURAL LANGUAGE MODE)
                   OR MATCH(j.description) AGAINST (COALESCE(:q, '') IN NATURAL LANGUAGE MODE)
                   OR EXISTS (SELECT 1 FROM job_skills s
                              WHERE s.job_id = j.id AND LOWER(s.skill) LIKE COALESCE(:qLike, '')))
              AND (:location IS NULL OR LOWER(j.location) LIKE :location)
              AND (:remote IS NULL OR j.remote = :remote)
              AND (:type IS NULL OR j.type = :type)
              AND (:seniority IS NULL OR j.seniority = :seniority)
              AND (:salaryMin IS NULL OR j.salary_max >= :salaryMin)
            """,
            nativeQuery = true)
    Page<Job> searchPublished(
            @Param("q") String q,
            @Param("qLike") String qLike,
            @Param("location") String location,
            @Param("remote") Boolean remote,
            @Param("type") String type,
            @Param("seniority") String seniority,
            @Param("salaryMin") Integer salaryMin,
            Pageable pageable);
}
