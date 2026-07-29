package com.jbp.serviceimpl;

import com.google.i18n.phonenumbers.PhoneNumberMatch;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.jbp.service.ResumeParser;
import com.jbp.util.ResumeTextExtractor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls out simple, deterministic suggestions (email, phone, known skills) from a resume.
 * No LLM; unreadable input yields none.
 *
 * <p>Always registered as a bean, even when {@code app.resume.parser=llm}, because
 * {@link LlmResumeParser} relies on it for two things: the contact details it must not ask a model
 * for, and the result it falls back to when the model is unavailable.
 */
@Service
public class DeterministicResumeParser implements ResumeParser {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    /**
     * Region assumed for numbers written without a country code. Most candidates are in India and
     * write their mobile as ten bare digits; anything carrying a {@code +} prefix is read as the
     * country it declares, so this only decides the local-format case.
     */
    private static final String DEFAULT_PHONE_REGION = "IN";

    // Small, fixed skills dictionary matched case-insensitively as substrings.
    private static final Set<String> KNOWN_SKILLS = Set.of(
            "Java", "Spring", "Spring Boot", "Hibernate", "SQL", "MySQL", "PostgreSQL",
            "JavaScript", "TypeScript", "React", "Angular", "Node.js", "Python",
            "AWS", "Docker", "Kubernetes", "Git", "REST", "Kafka", "Redis", "C++", "C#");

    private final ResumeTextExtractor resumeTextExtractor;
    private final PhoneNumberUtil phoneNumberUtil;

    public DeterministicResumeParser(ResumeTextExtractor resumeTextExtractor) {
        this.resumeTextExtractor = resumeTextExtractor;
        this.phoneNumberUtil = PhoneNumberUtil.getInstance();
    }

    @Override
    public ParsedResume parse(byte[] content, String contentType) {
        return parseText(resumeTextExtractor.extractText(content, contentType));
    }

    /**
     * Applies the regex, phone-number and dictionary rules to text that has already been
     * extracted, so a caller holding the text — {@link LlmResumeParser} — reuses these rules
     * without reading the file a second time.
     */
    public ParsedResume parseText(String text) {
        if (text == null || text.isBlank()) {
            return ParsedResume.empty();
        }
        return ParsedResume.ofContactDetails(
                firstMatch(EMAIL_PATTERN, text), findPhoneNumber(text), detectSkills(text));
    }

    private String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().trim() : null;
    }

    /**
     * Returns the first genuine phone number in the text, in E.164 form.
     *
     * <p>Delegates to libphonenumber rather than matching a pattern, because whether a digit run is
     * a phone number depends on the numbering plan it belongs to — an eight-digit string is a valid
     * number in some countries and a year range in a resume's education section. {@code VALID}
     * leniency means a candidate must be a real number somewhere, not merely look like one, which
     * is what rejects "2013 - 2017" without needing an arbitrary digit-count floor.
     *
     * <p>Normalised to E.164 rather than returned as written, for three reasons: it is canonical
     * regardless of how the candidate formatted it, it is unambiguous for a recruiter dialling
     * internationally, and it contains only {@code +} and digits — so applying the suggestion
     * always satisfies the character validation on
     * {@link com.jbp.dto.CandidateProfileRequest#getPhone()}. A raw match could contain dots or an
     * "ext." suffix and be rejected on save.
     */
    private String findPhoneNumber(String text) {
        for (PhoneNumberMatch match : phoneNumberUtil.findNumbers(text, DEFAULT_PHONE_REGION)) {
            return phoneNumberUtil.format(match.number(), PhoneNumberFormat.E164);
        }
        return null;
    }

    private Set<String> detectSkills(String text) {
        String lowerText = text.toLowerCase();
        Set<String> found = new LinkedHashSet<>();
        for (String skill : KNOWN_SKILLS) {
            if (lowerText.contains(skill.toLowerCase())) {
                found.add(skill);
            }
        }
        return found;
    }
}
