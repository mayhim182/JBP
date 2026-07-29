package com.jbp.serviceimpl;

import com.jbp.service.ResumeParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code app.resume.parser=llm} the LLM parser takes over, and the application still starts
 * cleanly with AI disabled — in which case every parse simply lands on the deterministic fallback.
 */
@SpringBootTest(properties = "app.resume.parser=llm")
class LlmParserSelectedByPropertyTest {

    @Autowired
    private ResumeParser resumeParser;

    @Test
    void usesTheLlmParserWhenAskedFor() {
        assertThat(resumeParser).isInstanceOf(LlmResumeParser.class);
    }
}
