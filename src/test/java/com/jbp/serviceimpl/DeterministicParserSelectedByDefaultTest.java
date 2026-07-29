package com.jbp.serviceimpl;

import com.jbp.service.ResumeParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With no {@code app.resume.parser} setting, resume parsing must stay on the rules it used before
 * AI existed — switching a candidate-facing behaviour on should always be a deliberate act.
 */
@SpringBootTest
class DeterministicParserSelectedByDefaultTest {

    @Autowired
    private ResumeParser resumeParser;

    @Test
    void usesTheDeterministicParserUnlessTheLlmOneIsAskedFor() {
        assertThat(resumeParser).isInstanceOf(DeterministicResumeParser.class);
    }
}
