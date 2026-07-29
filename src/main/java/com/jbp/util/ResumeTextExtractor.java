package com.jbp.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads the plain text out of a PDF or DOCX resume.
 *
 * <p>Lifted out of {@code DeterministicResumeParser} so both parsers share one implementation:
 * the LLM parser must send the model text and never the file itself, and duplicating PDFBox and
 * POI handling would mean two places to fix when a format quirk turns up.
 *
 * <p>Returns null rather than throwing on unreadable or unsupported input. Callers treat that as
 * "no suggestions available", which is the behaviour a candidate should see — a resume that
 * cannot be read must still upload successfully.
 */
@Component
public class ResumeTextExtractor {

    private static final String PDF_TYPE = "application/pdf";
    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /**
     * @return the document's text, or null if the type is unsupported or the file cannot be read
     */
    public String extractText(byte[] content, String contentType) {
        try {
            if (PDF_TYPE.equals(contentType)) {
                return extractPdfText(content);
            }
            if (DOCX_TYPE.equals(contentType)) {
                return extractDocxText(content);
            }
            return null;
        } catch (Exception unreadableFile) {
            // Graceful degradation: an unreadable resume simply yields no suggestions.
            return null;
        }
    }

    private String extractPdfText(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document) + hyperlinkTargets(document);
        }
    }

    private String extractDocxText(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    /**
     * Collects the addresses behind the document's hyperlinks, appended as extra lines.
     *
     * <p>Resumes almost always write "LinkedIn" or "GitHub" as clickable text with the address
     * hidden in a PDF link annotation. Annotations are not part of the text layer, so a text-only
     * extract yields the label and loses the address — and the label alone is useless as a profile
     * link. Appending the real targets means whatever reads this text, model or regex, can see them.
     *
     * <p>Deduplicated and order-preserving: a resume typically links the same profile from both a
     * header and a footer.
     */
    private String hyperlinkTargets(PDDocument document) throws IOException {
        Set<String> targets = new LinkedHashSet<>();
        for (PDPage page : document.getPages()) {
            for (PDAnnotation annotation : page.getAnnotations()) {
                addTargetIfPresent(annotation, targets);
            }
        }
        if (targets.isEmpty()) {
            return "";
        }
        return System.lineSeparator() + String.join(System.lineSeparator(), targets);
    }

    private void addTargetIfPresent(PDAnnotation annotation, Set<String> targets) {
        if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uriAction) {
            String target = uriAction.getURI();
            if (target != null && !target.isBlank()) {
                targets.add(target.trim());
            }
        }
    }
}
