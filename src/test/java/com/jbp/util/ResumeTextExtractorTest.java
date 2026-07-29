package com.jbp.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds PDFs in memory rather than checking fixture files in, so the hyperlink behaviour is
 * verified without a binary in the repository.
 */
class ResumeTextExtractorTest {

    private static final String PDF_TYPE = "application/pdf";

    private final ResumeTextExtractor extractor = new ResumeTextExtractor();

    @Test
    void recoversTheAddressHiddenBehindAHyperlink() throws IOException {
        byte[] resume = pdfLinkingTo("https://www.linkedin.com/in/example");

        assertThat(extractor.extractText(resume, PDF_TYPE))
                .as("the address lives in a link annotation, never in the text layer")
                .contains("https://www.linkedin.com/in/example");
    }

    @Test
    void collectsEveryDistinctAddress() throws IOException {
        byte[] resume = pdfLinkingTo("https://github.com/example", "https://example.dev");

        assertThat(extractor.extractText(resume, PDF_TYPE))
                .contains("https://github.com/example")
                .contains("https://example.dev");
    }

    @Test
    void reportsAnAddressOnceEvenWhenLinkedTwice() throws IOException {
        byte[] resume = pdfLinkingTo("https://github.com/example", "https://github.com/example");

        assertThat(extractor.extractText(resume, PDF_TYPE))
                .as("resumes commonly link the same profile from a header and a footer")
                .containsOnlyOnce("https://github.com/example");
    }

    @Test
    void returnsNullForAnUnsupportedContentType() {
        assertThat(extractor.extractText("anything".getBytes(), "image/png")).isNull();
    }

    @Test
    void returnsNullForBytesThatAreNotADocument() {
        assertThat(extractor.extractText("not a pdf at all".getBytes(), PDF_TYPE))
                .as("an unreadable resume must still upload, just without suggestions")
                .isNull();
    }

    private byte[] pdfLinkingTo(String... addresses) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            for (String address : addresses) {
                PDActionURI action = new PDActionURI();
                action.setURI(address);
                PDAnnotationLink link = new PDAnnotationLink();
                link.setAction(action);
                page.getAnnotations().add(link);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
