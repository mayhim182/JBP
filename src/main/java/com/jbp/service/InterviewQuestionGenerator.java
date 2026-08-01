package com.jbp.service;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.InterviewQuestionKind;
import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;
import com.jbp.util.TextHash;

import java.util.List;
import java.util.Set;

/**
 * Writes the questions a candidate is likely to be asked for a given job.
 *
 * <p>One capability, one interface — the same shape as {@link JobDescriptionGenerator},
 * {@link ResumeParser} and {@link MatchScorer}.
 *
 * <p><strong>This is the second AI feature that cannot degrade quietly</strong>, for the same reason
 * {@link JobDescriptionGenerator} cannot: the entire output is the model's. There is no rule-based
 * set of interview questions worth showing, and inventing a canned list would be worse than showing
 * nothing — a candidate who prepares eight generic answers and meets none of them in the room has
 * been actively misled. So this throws, the handler answers 503, and the client draws design 21b's
 * state D.
 *
 * <p><strong>Deliberately not personalised.</strong> The input is the job and nothing about the
 * viewer, which is what makes one cached answer correct for every candidate — and what lets the UI
 * say so plainly: <em>"Same questions for every candidate — cached per job."</em> Adding the
 * candidate's profile here would multiply provider cost by the number of viewers and quietly make
 * that line false.
 */
public interface InterviewQuestionGenerator {

    /**
     * @throws LlmUnavailableException when AI is off, this capability is off, the provider is
     *                                unreachable, or the reply could not be used
     */
    InterviewQuestions generate(JobBrief brief);

    /**
     * Everything the model is told. A record because it is assembled per call and never mutated, and
     * nested here because it exists only as this interface's input.
     *
     * <p>No candidate fields, by design — see the class note.
     */
    record JobBrief(String title,
                    String description,
                    Set<String> skills,
                    SeniorityLevel seniority,
                    JobType type) {

        /**
         * The cache key: a fingerprint of exactly what the model is told.
         *
         * <p>Not the job id, which would serve the old questions forever after a recruiter edited the
         * posting. Not {@code EmbeddingTexts.forJob} either, though that was the first proposal — it
         * carries location, which cannot change an interview question, and omits employment type,
         * which can. Hashing the brief itself means the key changes when, and only when, the input to
         * the model changes, so a cache hit is provably the same question set.
         *
         * <p>Skills are sorted for the same reason {@code EmbeddingTexts} sorts them: they arrive in a
         * {@code HashSet} whose iteration order is not stable across JVM runs, and an unsorted hash
         * would miss the cache on every restart.
         */
        public String cacheKey() {
            String sortedSkills = skills == null ? "" : skills.stream()
                    .filter(java.util.Objects::nonNull)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            return TextHash.sha256Hex(String.join("|",
                    String.valueOf(title),
                    String.valueOf(description),
                    sortedSkills,
                    String.valueOf(seniority),
                    String.valueOf(type)));
        }
    }

    /**
     * The generated set, already grouped and ordered.
     *
     * <p>Groups arrive in {@link InterviewQuestionKind} declaration order with empty ones removed, so
     * a client renders them in sequence and never has to sort or skip. Design 21 requires an empty
     * group to produce <em>no overline</em> rather than an overline with nothing under it, which is
     * simplest to guarantee by not sending it at all.
     */
    record InterviewQuestions(List<QuestionGroup> groups) {

        /** Total across all groups — the acceptance criterion's 5-8, and what validation checks. */
        public int total() {
            return groups.stream().mapToInt(group -> group.questions().size()).sum();
        }
    }

    record QuestionGroup(InterviewQuestionKind kind, List<String> questions) {
    }
}
