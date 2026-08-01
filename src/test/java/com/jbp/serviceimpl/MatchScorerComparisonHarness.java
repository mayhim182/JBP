package com.jbp.serviceimpl;

import com.jbp.model.CandidateProfile;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.Experience;
import com.jbp.model.Job;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.EmbeddingClient;
import com.jbp.service.EmbeddingStore;
import com.jbp.service.MatchScorer.MatchResult;
import com.jbp.util.CosineSimilarity;
import com.jbp.util.EmbeddingTexts;
import com.jbp.util.SemanticScoreCalibration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.4's comparison harness: scores one fixture set with all three scorers against <em>real</em>
 * embeddings, and writes {@code docs/match-scoring-comparison.md} for committing.
 *
 * <p><strong>Why this exists rather than a unit test.</strong> Story 13.3 shipped a calibration band of
 * 0.55-0.90 derived from two short phrases. Run against real data on 2026-08-01, a candidate/job pair the
 * rule scorer rates 92 scored 11. Short phrases and real documents do not behave the same way, and no
 * mocked test can discover that — only real vectors can. Everything this file measures is a number that
 * had to come from the provider.
 *
 * <p><strong>What it settles.</strong> Two things, in one run:
 * <ol>
 *   <li><strong>The band.</strong> The floor is the highest cosine any genuinely unrelated pair reaches —
 *       anything at or below it must score zero. The ceiling is the mean over pairs that are the same job
 *       described the same way, so a real strong match lands near 100 rather than in the thirties.</li>
 *   <li><strong>Whether the band is even the problem.</strong> Every pair is embedded twice: once from
 *       {@link EmbeddingTexts} as production builds it, and once from a trimmed "core" form. The metric
 *       that matters is <em>separation</em> — mean strong cosine minus worst unrelated cosine — because
 *       raising every cosine equally would change nothing a band cannot. If core separates better, the
 *       embedded text is diluting the signal and no property will fix it.</li>
 * </ol>
 *
 * <p>Skipped unless {@code JBP_AI_LIVE_TEST=true}, like {@link GeminiLiveSmokeTest}: it spends free-tier
 * quota and needs the network. Two batched provider calls, well inside the 12-per-minute limit.
 *
 * <p><strong>Production code is deliberately untouched.</strong> The core-text variant lives here, not in
 * {@code EmbeddingTexts}, because changing what gets embedded on an unproven hypothesis would invalidate
 * every stored vector to test a guess. If the evidence supports it, that becomes its own story with its
 * own re-embed.
 */
@SpringBootTest(properties = {
        "app.ai.enabled=true",
        "app.ai.base-url=https://generativelanguage.googleapis.com/v1beta/openai",
        "app.ai.embedding-model=gemini-embedding-001",
        "app.ai.embedding-dimensions=768",
        "app.ai.api-key=${GEMINI_API_KEY:}"
})
@EnabledIfEnvironmentVariable(named = "JBP_AI_LIVE_TEST", matches = "true")
class MatchScorerComparisonHarness {

    private static final Path REPORT = Path.of("docs", "match-scoring-comparison.md");

    /** The band Story 13.3 shipped, kept here as the "before" column. */
    private static final SemanticScoreCalibration SHIPPED_BAND = new SemanticScoreCalibration(0.55, 0.90);

    private static final int RULE_WEIGHT = 70;

    /** Proven at 3 inputs; 16 stays well inside the batch size the store already uses. */
    private static final int TEXTS_PER_CALL = 16;

    private static final long PROFILE_ID = 1L;
    private static final long JOB_ID = 2L;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Test
    void scoresEveryPairWithAllThreeScorersAndDerivesTheBandFromRealVectors() throws IOException {
        List<Pair> pairs = fixturePairs();
        Map<String, float[]> vectors = embedAll(textsOf(pairs));

        List<Measurement> measurements = new ArrayList<>();
        for (Pair pair : pairs) {
            measurements.add(measure(pair, vectors));
        }

        DerivedBand derived = deriveBandFrom(measurements);
        Separation full = separationOf(measurements, false);
        Separation core = separationOf(measurements, true);

        String report = renderReport(measurements, derived, full, core);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);
        System.out.println(report);

        assertThat(derived.ceiling())
                .as("a fixture set where strong pairs do not out-score unrelated ones cannot calibrate "
                        + "anything, and the whole epic's premise would be wrong")
                .isGreaterThan(derived.floor());
        assertThat(worstOf(measurements, Relationship.STRONG))
                .as("every same-role pair must beat every unrelated pair")
                .isGreaterThan(bestOf(measurements, Relationship.UNRELATED));
        assertThat(REPORT).exists();
    }

    // ---------------------------------------------------------------- measuring

    private Measurement measure(Pair pair, Map<String, float[]> vectors) {
        float[] profileFull = vectors.get(EmbeddingTexts.forCandidateProfile(pair.profile()));
        float[] jobFull = vectors.get(EmbeddingTexts.forJob(pair.job()));
        double cosineFull = CosineSimilarity.between(profileFull, jobFull);
        double cosineCore = CosineSimilarity.between(
                vectors.get(coreTextFor(pair.profile())), vectors.get(coreTextFor(pair.job())));

        EmbeddingStore store = storeReturning(profileFull, jobFull);
        RuleBasedMatchScorer ruleBased = new RuleBasedMatchScorer();

        return new Measurement(
                pair,
                cosineFull,
                cosineCore,
                ruleBased.score(pair.profile(), pair.job()),
                new EmbeddingMatchScorer(store, ruleBased, SHIPPED_BAND).score(pair.profile(), pair.job()),
                new HybridMatchScorer(store, ruleBased, SHIPPED_BAND, RULE_WEIGHT)
                        .score(pair.profile(), pair.job()));
    }

    /**
     * Floor from the worst case that must score zero, ceiling from the average case that should score
     * near 100. Deriving the ceiling from the <em>maximum</em> strong cosine would let one unusually
     * similar pair define the top of the scale and push every other real match down.
     */
    private DerivedBand deriveBandFrom(List<Measurement> measurements) {
        return new DerivedBand(
                roundedToThreeDecimals(bestOf(measurements, Relationship.UNRELATED)),
                roundedToThreeDecimals(meanOf(measurements, Relationship.STRONG, false)));
    }

    private Separation separationOf(List<Measurement> measurements, boolean core) {
        double strong = meanOf(measurements, Relationship.STRONG, core);
        double unrelated = measurements.stream()
                .filter(measurement -> measurement.pair().expected() == Relationship.UNRELATED)
                .mapToDouble(measurement -> measurement.cosine(core))
                .max()
                .orElseThrow();
        return new Separation(strong, unrelated);
    }

    private double meanOf(List<Measurement> measurements, Relationship relationship, boolean core) {
        return measurements.stream()
                .filter(measurement -> measurement.pair().expected() == relationship)
                .mapToDouble(measurement -> measurement.cosine(core))
                .average()
                .orElseThrow();
    }

    private double bestOf(List<Measurement> measurements, Relationship relationship) {
        return measurements.stream()
                .filter(measurement -> measurement.pair().expected() == relationship)
                .mapToDouble(Measurement::cosineFull)
                .max()
                .orElseThrow();
    }

    private double worstOf(List<Measurement> measurements, Relationship relationship) {
        return measurements.stream()
                .filter(measurement -> measurement.pair().expected() == relationship)
                .mapToDouble(Measurement::cosineFull)
                .min()
                .orElseThrow();
    }

    private double roundedToThreeDecimals(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    // ---------------------------------------------------------------- embedding

    /** Distinct texts only: the same job appears in several pairs and its vector never changes. */
    private Set<String> textsOf(List<Pair> pairs) {
        Set<String> texts = new LinkedHashSet<>();
        for (Pair pair : pairs) {
            texts.add(EmbeddingTexts.forCandidateProfile(pair.profile()));
            texts.add(EmbeddingTexts.forJob(pair.job()));
            texts.add(coreTextFor(pair.profile()));
            texts.add(coreTextFor(pair.job()));
        }
        return texts;
    }

    private Map<String, float[]> embedAll(Set<String> texts) {
        List<String> ordered = List.copyOf(texts);
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (int start = 0; start < ordered.size(); start += TEXTS_PER_CALL) {
            List<String> batch = ordered.subList(start, Math.min(start + TEXTS_PER_CALL, ordered.size()));
            List<float[]> embedded = embeddingClient.embedAll(batch);
            assertThat(embedded).hasSameSizeAs(batch);
            for (int index = 0; index < batch.size(); index++) {
                vectors.put(batch.get(index), embedded.get(index));
            }
        }
        System.out.printf("Embedded %d distinct texts in %d call(s)%n",
                vectors.size(), (vectors.size() + TEXTS_PER_CALL - 1) / TEXTS_PER_CALL);
        return vectors;
    }

    /**
     * The trimmed variant: what the role <em>is</em>, with no prose. Production embeds a job's whole
     * description — up to 5000 characters — against a profile that is often a headline and a skill list,
     * and that length asymmetry is the leading explanation for a strong pair scoring 0.589.
     */
    private String coreTextFor(CandidateProfile profile) {
        return joinNonBlank(profile.getHeadline(), sortedSkillsOf(profile.getSkills()));
    }

    private String coreTextFor(Job job) {
        return joinNonBlank(job.getTitle(), sortedSkillsOf(job.getSkills()));
    }

    /** Sorted for the same reason {@link EmbeddingTexts} sorts: a {@code HashSet}'s order is not stable. */
    private String sortedSkillsOf(Set<String> skills) {
        return skills == null ? "" : String.join(", ", new TreeSet<>(skills));
    }

    /** {@code Stream.of} rather than {@code List.of}, which rejects the nulls an absent headline produces. */
    private String joinNonBlank(String... parts) {
        return Stream.of(parts)
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(". "));
    }

    private EmbeddingStore storeReturning(float[] profileVector, float[] jobVector) {
        EmbeddingStore store = Mockito.mock(EmbeddingStore.class);
        Mockito.when(store.findVector(EmbeddingOwnerType.CANDIDATE_PROFILE, PROFILE_ID))
                .thenReturn(Optional.of(profileVector));
        Mockito.when(store.findVector(EmbeddingOwnerType.JOB, JOB_ID))
                .thenReturn(Optional.of(jobVector));
        return store;
    }

    // ---------------------------------------------------------------- reporting

    private String renderReport(List<Measurement> measurements, DerivedBand derived,
                                Separation full, Separation core) {
        SemanticScoreCalibration derivedBand =
                new SemanticScoreCalibration(derived.floor(), derived.ceiling());
        StringBuilder report = new StringBuilder();

        report.append("# Match scoring comparison — Story 13.4\n\n")
                .append("Generated by `MatchScorerComparisonHarness` against the live provider on ")
                .append(LocalDate.now()).append(".\n")
                .append("Regenerate with `JBP_AI_LIVE_TEST=true ./mvnw test ")
                .append("-Dtest=MatchScorerComparisonHarness`.\n\n")
                .append("Model `gemini-embedding-001` at 768 dimensions. Hybrid weighting ")
                .append(RULE_WEIGHT).append(" rules / ").append(100 - RULE_WEIGHT).append(" meaning.\n\n");

        report.append("## Scores per pair\n\n")
                .append("`semantic*` is the semantic row rescaled with the derived band below, showing what ")
                .append("the same cosine would be worth once calibrated on real documents.\n\n")
                .append("| pair | expected | cosine (full) | cosine (core) | rule | embedding | hybrid |")
                .append(" semantic* |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Measurement measurement : measurements) {
            report.append(String.format("| %s | %s | %.3f | %.3f | %d | %d | %d | %d |%n",
                    measurement.pair().label(),
                    measurement.pair().expected(),
                    measurement.cosineFull(),
                    measurement.cosineCore(),
                    measurement.rule().score(),
                    measurement.embedding().score(),
                    measurement.hybrid().score(),
                    derivedBand.toScore(measurement.cosineFull())));
        }

        report.append("\n## Derived calibration band\n\n")
                .append("| | floor | ceiling |\n| --- | --- | --- |\n")
                .append(String.format("| shipped in 13.3 (from two short phrases) | %.3f | %.3f |%n",
                        SHIPPED_BAND.floor(), SHIPPED_BAND.ceiling()))
                .append(String.format("| derived here (from real documents) | %.3f | %.3f |%n",
                        derived.floor(), derived.ceiling()))
                .append("\nFloor = the highest cosine reached by any unrelated pair, so everything at or ")
                .append("below it scores 0.\nCeiling = the mean over same-role, same-wording pairs, so a ")
                .append("real strong match lands near 100.\n\n")
                .append("```properties\napp.match.semantic-floor=")
                .append(String.format("%.3f", derived.floor()))
                .append("\napp.match.semantic-ceiling=")
                .append(String.format("%.3f", derived.ceiling()))
                .append("\n```\n\n");

        report.append("## Is it the band or the embedded text?\n\n")
                .append("Separation is the metric, not absolute cosine: lifting every pair equally would ")
                .append("change nothing that a band cannot already fix.\n\n")
                .append("| text form | mean strong | worst unrelated | separation |\n")
                .append("| --- | --- | --- | --- |\n")
                .append(String.format("| full (`EmbeddingTexts`, in production) | %.3f | %.3f | %.3f |%n",
                        full.meanStrong(), full.worstUnrelated(), full.separation()))
                .append(String.format("| core (title/headline + skills only) | %.3f | %.3f | %.3f |%n",
                        core.meanStrong(), core.worstUnrelated(), core.separation()))
                .append("\n**").append(verdictOn(full, core)).append("**\n");

        return report.toString();
    }

    /**
     * Stated as a threshold rather than "core is higher", because two nearly equal separations are
     * evidence <em>against</em> changing what gets embedded — a re-embed of every row is too expensive to
     * justify on noise.
     */
    private String verdictOn(Separation full, Separation core) {
        double improvement = core.separation() - full.separation();
        if (improvement > 0.05) {
            return String.format("Verdict: the embedded text is diluting the signal. Core text separates "
                    + "%.3f better, which a calibration band cannot recover. Raise a story to narrow "
                    + "EmbeddingTexts and re-embed.", improvement);
        }
        if (improvement < -0.05) {
            return String.format("Verdict: the full text is the better input — core separates %.3f worse. "
                    + "EmbeddingTexts stays as it is; the band is the whole fix.", -improvement);
        }
        return String.format("Verdict: the two text forms separate within %.3f of each other, so the "
                + "embedded text is not the problem. Re-deriving the band is the whole fix, and narrowing "
                + "EmbeddingTexts would cost a full re-embed for no measured gain.", Math.abs(improvement));
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Eight pairs spanning the four relationships. Small on purpose: every pair costs provider quota, and
     * the numbers that matter — the worst unrelated cosine and the mean strong cosine — are stable well
     * before a set this size stops being hand-checkable.
     */
    private List<Pair> fixturePairs() {
        Job reactJob = job("React Developer",
                "Build and maintain customer-facing single page applications. You will own component "
                        + "architecture, state management and performance of our web client.",
                Set.of("react", "javascript", "css"), "Pune", SeniorityLevel.MID);
        Job backendJob = job("Senior Backend Engineer",
                "Design and operate the REST services behind our platform. Ownership of schema design, "
                        + "query performance and release quality.",
                Set.of("java", "spring", "mysql"), "Pune", SeniorityLevel.SENIOR);
        Job dataJob = job("Data Engineer",
                "Build batch and streaming pipelines that land clean data in the warehouse, and own the "
                        + "orchestration and data quality checks around them.",
                Set.of("python", "spark", "sql"), "Bengaluru", SeniorityLevel.MID);
        Job mechanicJob = job("Diesel Mechanic",
                "Service and repair heavy earthmoving equipment on site. Diagnose hydraulic and engine "
                        + "faults and complete scheduled maintenance.",
                Set.of("hydraulics", "diesel engines"), "Nagpur", SeniorityLevel.MID);

        return List.of(
                new Pair("react-dev / react-frontend", Relationship.STRONG,
                        profile("React frontend developer", Set.of("react", "javascript", "css"), "Pune",
                                SeniorityLevel.MID,
                                experience("Frontend Developer", "Zeta",
                                        "Built React components and managed client-side state.")),
                        reactJob),
                new Pair("react-dev / spa-builder", Relationship.RELATED,
                        profile("Engineer who builds single page apps", Set.of("typescript", "redux"),
                                "Pune", SeniorityLevel.MID,
                                experience("Web Engineer", "Zeta",
                                        "Built rich browser applications and owned their rendering "
                                                + "performance.")),
                        reactJob),
                new Pair("backend / java-spring", Relationship.STRONG,
                        profile("Backend engineer, Java and Spring", Set.of("java", "spring", "mysql"),
                                "Pune", SeniorityLevel.SENIOR,
                                experience("Backend Engineer", "Innoviti",
                                        "Built and operated REST services and owned their schema design.")),
                        backendJob),
                new Pair("backend / api-builder", Relationship.RELATED,
                        profile("Server-side developer building web APIs", Set.of("kotlin", "postgres"),
                                "Pune", SeniorityLevel.SENIOR,
                                experience("Software Engineer", "Innoviti",
                                        "Designed HTTP endpoints and tuned database queries.")),
                        backendJob),
                new Pair("data / pipelines", Relationship.STRONG,
                        profile("Data engineer building pipelines", Set.of("python", "spark", "sql"),
                                "Bengaluru", SeniorityLevel.MID,
                                experience("Data Engineer", "Meesho",
                                        "Built Spark batch jobs landing data in the warehouse.")),
                        dataJob),
                new Pair("react-dev / backend-profile", Relationship.WEAK,
                        profile("Backend engineer, Java and Spring", Set.of("java", "spring", "mysql"),
                                "Pune", SeniorityLevel.MID,
                                experience("Backend Engineer", "Innoviti",
                                        "Built and operated REST services and owned their schema design.")),
                        reactJob),
                new Pair("mechanic / react-frontend", Relationship.UNRELATED,
                        profile("React frontend developer", Set.of("react", "javascript", "css"), "Pune",
                                SeniorityLevel.MID,
                                experience("Frontend Developer", "Zeta",
                                        "Built React components and managed client-side state.")),
                        mechanicJob),
                new Pair("mechanic / data-engineer", Relationship.UNRELATED,
                        profile("Data engineer building pipelines", Set.of("python", "spark", "sql"),
                                "Bengaluru", SeniorityLevel.MID,
                                experience("Data Engineer", "Meesho",
                                        "Built Spark batch jobs landing data in the warehouse.")),
                        mechanicJob));
    }

    private CandidateProfile profile(String headline, Set<String> skills, String location,
                                     SeniorityLevel seniority, Experience... experiences) {
        return CandidateProfile.builder()
                .id(PROFILE_ID)
                .headline(headline)
                .skills(skills)
                .location(location)
                .seniority(seniority)
                .experiences(List.of(experiences))
                .build();
    }

    private Job job(String title, String description, Set<String> skills, String location,
                    SeniorityLevel seniority) {
        return Job.builder()
                .id(JOB_ID)
                .title(title)
                .description(description)
                .skills(skills)
                .location(location)
                .seniority(seniority)
                .build();
    }

    private Experience experience(String title, String company, String description) {
        return Experience.builder().title(title).company(company).description(description).build();
    }

    // ---------------------------------------------------------------- types

    /** How similar a pair ought to be, decided by reading it — the only judgement in the harness. */
    private enum Relationship {
        /** Same role, described the same way. Defines the top of the usable band. */
        STRONG,
        /** Same role, different vocabulary. The case Epic 13 exists for; must not score zero. */
        RELATED,
        /** Adjacent discipline. Should land in the middle rather than at either end. */
        WEAK,
        /** Different industry. Defines the floor: must score zero. */
        UNRELATED
    }

    private record Pair(String label, Relationship expected, CandidateProfile profile, Job job) {
    }

    private record Measurement(Pair pair, double cosineFull, double cosineCore,
                               MatchResult rule, MatchResult embedding, MatchResult hybrid) {

        double cosine(boolean core) {
            return core ? cosineCore : cosineFull;
        }
    }

    private record DerivedBand(double floor, double ceiling) {
    }

    private record Separation(double meanStrong, double worstUnrelated) {

        double separation() {
            return meanStrong - worstUnrelated;
        }
    }
}
