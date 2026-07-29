package com.jbp.serviceimpl;

import com.jbp.model.SeniorityLevel;
import com.jbp.service.ResumeParser;
import com.jbp.serviceimpl.ResumeExtractionTask.ResumeExtraction;
import com.jbp.util.ResumeTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a resume with the help of a language model, so a candidate gets experience, education and
 * projects back rather than only skills.
 *
 * <p>Selected by {@code app.resume.parser=llm}; {@link DeterministicResumeParser} serves otherwise.
 * Marked {@link Primary} because the deterministic parser stays registered either way — this class
 * needs it, and callers inject {@link ResumeParser} by type without knowing which is active.
 *
 * <p>The model never sees the file, only the text {@link ResumeTextExtractor} produced. It is also
 * never asked for an email or phone number: regex finds those exactly, where a model would only be
 * plausible, and a wrong contact detail on a profile is worse than none.
 *
 * <p>Every failure path lands on the deterministic result. A model that is unavailable, disabled,
 * slow or talking nonsense produces an empty extraction, and merging an empty extraction with the
 * deterministic suggestions returns exactly those suggestions — so the upload always succeeds and
 * the candidate is never worse off than before this class existed.
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.resume.parser", havingValue = "llm")
public class LlmResumeParser implements ResumeParser {

    private static final Logger log = LoggerFactory.getLogger(LlmResumeParser.class);

    /** Below this, there is not enough text for a model to say anything worth reviewing. */
    private static final int MINIMUM_USEFUL_TEXT_LENGTH = 200;

    /**
     * Proportion of characters that must be ordinary letters, digits, whitespace or punctuation.
     * A scanned or image-only PDF yields mostly control characters and replacement glyphs.
     */
    private static final double MINIMUM_READABLE_CHARACTER_RATIO = 0.85;

    /**
     * Real prose averages five or six characters a word. A two-column PDF read in the wrong order
     * runs words together into far longer tokens, which no prompt can recover.
     */
    private static final int IMPLAUSIBLE_AVERAGE_WORD_LENGTH = 20;

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * An address needs a domain — some text, a dot, and a two-letter-or-longer suffix — optionally
     * followed by a path. "linkedin.com/in/example" qualifies; the bare word "LinkedIn" does not.
     */
    private static final Pattern LOOKS_LIKE_WEB_ADDRESS =
            Pattern.compile("\\S+\\.[A-Za-z]{2,}(/\\S*)?");

    private final ResumeTextExtractor resumeTextExtractor;
    private final DeterministicResumeParser deterministicResumeParser;
    private final ResumeExtractionTask resumeExtractionTask;

    public LlmResumeParser(ResumeTextExtractor resumeTextExtractor,
                           DeterministicResumeParser deterministicResumeParser,
                           ResumeExtractionTask resumeExtractionTask) {
        this.resumeTextExtractor = resumeTextExtractor;
        this.deterministicResumeParser = deterministicResumeParser;
        this.resumeExtractionTask = resumeExtractionTask;
    }

    @Override
    public ParsedResume parse(byte[] content, String contentType) {
        String resumeText = resumeTextExtractor.extractText(content, contentType);
        ParsedResume deterministicSuggestions = deterministicResumeParser.parseText(resumeText);
        if (!isWorthSendingToModel(resumeText)) {
            log.debug("Resume text is low signal; keeping deterministic suggestions only");
            return deterministicSuggestions;
        }
        return merge(deterministicSuggestions, resumeExtractionTask.execute(resumeText));
    }

    /**
     * Combines the two sources, each contributing what it is better at: regex for contact details,
     * the model for the sections that need reading comprehension.
     */
    private ParsedResume merge(ParsedResume deterministicSuggestions, ResumeExtraction extraction) {
        return new ParsedResume(
                deterministicSuggestions.email(),
                deterministicSuggestions.phone(),
                mergeSkills(extraction.skills(), deterministicSuggestions.skills()),
                blankToNull(extraction.headline()),
                blankToNull(extraction.location()),
                toSeniorityLevel(extraction.seniority()),
                withoutNulls(extraction.experiences()),
                withoutNulls(extraction.educations()),
                withoutNulls(extraction.projects()),
                normalisedLinks(extraction.links()));
    }

    /**
     * Model skills first, then any dictionary matches it missed. Deduplicated case-insensitively,
     * so "react" and "React" cannot both appear, while the casing the model chose is preserved.
     * The dictionary is no longer a gate on what counts as a skill — it only adds to the result.
     */
    private Set<String> mergeSkills(List<String> modelSkills, Set<String> deterministicSkills) {
        Set<String> merged = new LinkedHashSet<>();
        Set<String> alreadySeen = new HashSet<>();
        addNormalised(modelSkills, merged, alreadySeen);
        addNormalised(deterministicSkills, merged, alreadySeen);
        return merged;
    }

    private void addNormalised(Collection<String> values, Set<String> target, Set<String> alreadySeen) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            String normalised = normalise(value);
            if (!normalised.isEmpty() && alreadySeen.add(normalised.toLowerCase(Locale.ROOT))) {
                target.add(normalised);
            }
        }
    }

    /**
     * Keeps only suggestions that are actually addresses.
     *
     * <p>A resume that writes "LinkedIn" as clickable text hides the address in a PDF annotation,
     * which {@link ResumeTextExtractor} now appends to the text — but if it is missing for any
     * reason, the model can only offer the visible label. Storing "LinkedIn" as a profile link
     * would put something unusable on the candidate's profile, so a label is dropped rather than
     * kept.
     */
    private List<String> normalisedLinks(List<String> links) {
        Set<String> normalised = new LinkedHashSet<>();
        addNormalised(links, normalised, new HashSet<>());
        return normalised.stream()
                .filter(candidate -> LOOKS_LIKE_WEB_ADDRESS.matcher(candidate).matches())
                .toList();
    }

    private String normalise(String value) {
        return value == null ? "" : WHITESPACE_RUN.matcher(value.trim()).replaceAll(" ");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Maps the model's text to the enum, or to null when it answers with something unrecognised —
     * so a free-form seniority never reaches the profile.
     */
    private SeniorityLevel toSeniorityLevel(String seniority) {
        if (seniority == null || seniority.isBlank()) {
            return null;
        }
        try {
            return SeniorityLevel.valueOf(seniority.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognisedLevel) {
            log.debug("Model answered with an unrecognised seniority; leaving it unset");
            return null;
        }
    }

    private <T> List<T> withoutNulls(List<T> entries) {
        if (entries == null) {
            return List.of();
        }
        List<T> present = new ArrayList<>(entries.size());
        for (T entry : entries) {
            if (entry != null) {
                present.add(entry);
            }
        }
        return List.copyOf(present);
    }

    /**
     * Whether the extracted text is worth spending a request on. Garbled or near-empty text cannot
     * produce a useful answer, and on a free tier a wasted call is a call another candidate does
     * not get.
     */
    private boolean isWorthSendingToModel(String resumeText) {
        if (resumeText == null || resumeText.strip().length() < MINIMUM_USEFUL_TEXT_LENGTH) {
            return false;
        }
        return readableCharacterRatio(resumeText) >= MINIMUM_READABLE_CHARACTER_RATIO
                && averageWordLength(resumeText) <= IMPLAUSIBLE_AVERAGE_WORD_LENGTH;
    }

    private double readableCharacterRatio(String text) {
        long readable = text.chars().filter(this::isReadable).count();
        return (double) readable / text.length();
    }

    private boolean isReadable(int character) {
        return Character.isLetterOrDigit(character)
                || Character.isWhitespace(character)
                || "-.,:;/()&+#'\"@_*[]".indexOf(character) >= 0;
    }

    private double averageWordLength(String text) {
        String[] words = WHITESPACE_RUN.split(text.strip());
        if (words.length == 0) {
            return 0;
        }
        long totalLength = 0;
        for (String word : words) {
            totalLength += word.length();
        }
        return (double) totalLength / words.length;
    }
}
