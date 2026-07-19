package com.jbp.serviceimpl;

import com.jbp.service.ResumeParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts text from PDF/DOCX resumes and pulls out simple, deterministic
 * suggestions (email, phone, known skills). No LLM; unreadable input yields none.
 */
@Service
public class DeterministicResumeParser implements ResumeParser {

    private static final String PDF_TYPE = "application/pdf";
    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\+?\\d[\\d\\s-]{7,}\\d");

    // Small, fixed skills dictionary matched case-insensitively as substrings.
    private static final Set<String> KNOWN_SKILLS = Set.of(
            "Java", "Spring", "Spring Boot", "Hibernate", "SQL", "MySQL", "PostgreSQL",
            "JavaScript", "TypeScript", "React", "Angular", "Node.js", "Python",
            "AWS", "Docker", "Kubernetes", "Git", "REST", "Kafka", "Redis", "C++", "C#");

    @Override
    public ParsedResume parse(byte[] content, String contentType) {
        String text = extractText(content, contentType);
        if (text == null || text.isBlank()) {
            return ParsedResume.empty();
        }
        return new ParsedResume(firstMatch(EMAIL_PATTERN, text), firstMatch(PHONE_PATTERN, text), detectSkills(text));
    }

    private String extractText(byte[] content, String contentType) {
        try {
            if (PDF_TYPE.equals(contentType)) {
                try (PDDocument document = Loader.loadPDF(content)) {
                    return new PDFTextStripper().getText(document);
                }
            }
            if (DOCX_TYPE.equals(contentType)) {
                try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    return extractor.getText();
                }
            }
            return null;
        } catch (Exception e) {
            // Graceful degradation: an unreadable resume simply yields no suggestions.
            return null;
        }
    }

    private String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().trim() : null;
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
