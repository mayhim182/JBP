package com.jbp.serviceimpl;

import com.jbp.service.ResumeParser.ParsedResume;
import com.jbp.util.ResumeTextExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the regex, phone-number and dictionary rules directly through {@code parseText}, so no
 * PDF or DOCX is needed — the extractor is never consulted on this path.
 *
 * <p>Phone numbers come back in E.164 form. libphonenumber validates against real numbering plans,
 * so the numbers used here are plausible ones rather than made-up digits.
 */
class DeterministicResumeParserTest {

    private final DeterministicResumeParser parser =
            new DeterministicResumeParser(new ResumeTextExtractor());

    @Test
    void normalisesAnIndianMobileWrittenWithACountryCodeAndHyphen() {
        assertThat(parser.parseText("Call me on +91-9891207803 any time").phone())
                .isEqualTo("+919891207803");
    }

    @Test
    void addsTheCountryCodeToABareLocalNumber() {
        assertThat(parser.parseText("Phone: 98765 43210").phone())
                .as("candidates write ten bare digits; India is the assumed region")
                .isEqualTo("+919876543210");
    }

    @Test
    void readsAnInternationalNumberContainingParentheses() {
        assertThat(parser.parseText("Tel +44 (0)20 7946 0018 (office)").phone())
                .as("parentheses are common internationally and defeated the old pattern")
                .startsWith("+44");
    }

    @Test
    void doesNotMistakeAnEducationDateRangeForAPhoneNumber() {
        assertThat(parser.parseText("B.Tech, Anna University, 2013 - 2017").phone())
                .as("eight digits is not a valid number in any plan")
                .isNull();
    }

    @Test
    void skipsADateRangeAndKeepsLookingForTheRealNumber() {
        ParsedResume parsed =
                parser.parseText("Anna University 2013 - 2017. Reach me on 9891207803.");

        assertThat(parsed.phone()).isEqualTo("+919891207803");
    }

    @Test
    void suggestsNoPhoneWhenThereIsNone() {
        assertThat(parser.parseText("Experienced engineer with no contact details listed").phone())
                .isNull();
    }

    @Test
    void findsAnEmailAddress() {
        assertThat(parser.parseText("Write to nazirul.hashar@example.co.in").email())
                .isEqualTo("nazirul.hashar@example.co.in");
    }

    @Test
    void detectsDictionarySkillsCaseInsensitively() {
        ParsedResume parsed = parser.parseText("Worked with java, SPRING BOOT and docker daily.");

        assertThat(parsed.skills()).contains("Java", "Spring Boot", "Docker");
    }

    @Test
    void suggestsNothingForTextItCannotRead() {
        assertThat(parser.parseText(null)).isEqualTo(ParsedResume.empty());
        assertThat(parser.parseText("   ")).isEqualTo(ParsedResume.empty());
    }
}
