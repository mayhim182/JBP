package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.ResumeParser.ParsedResume;
import com.jbp.util.ResumeTextExtractor;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmResumeParserTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    private static final String PDF_TYPE = "application/pdf";
    private static final byte[] ANY_CONTENT = "irrelevant, the extractor is stubbed".getBytes();

    /** Long enough and clean enough to pass the low-signal check, and carries regex-findable details. */
    private static final String READABLE_RESUME = """
            Nazirul Hashar
            nazirul@example.com
            +91 98765 43210
            Bengaluru, India

            Senior Software Engineer with eight years building backend services in Java and Spring.
            Experience with MySQL, Docker and Kubernetes across payments and hiring products.
            Led a team of four engineers delivering a migration from a monolith to services.
            Education: B.Tech in Computer Science, Anna University, 2013 to 2017.
            """;

    private static final String FULL_EXTRACTION = """
            {
              "headline": "Senior Software Engineer",
              "location": "Bengaluru, India",
              "seniority": "senior",
              "skills": ["React", "  react  ", "GraphQL", "Java", ""],
              "experiences": [{"title":"Senior Software Engineer","company":"Acme",
                               "startDate":"Mar 2021","endDate":"Present","description":"Backend services"}],
              "educations": [{"institution":"Anna University","degree":"B.Tech",
                              "fieldOfStudy":"Computer Science","startYear":"2013","endYear":"2017"}],
              "projects": [{"name":"Toolpath viewer","description":"3D viewer","link":"https://example.com"}],
              "links": ["https://github.com/example", "https://github.com/example"]
            }
            """;

    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void returnsTheModelsSectionsAlongsideRegexContactDetails() {
        ParsedResume parsed = parserWithModelReply(FULL_EXTRACTION).parse(ANY_CONTENT, PDF_TYPE);

        assertThat(parsed.email()).isEqualTo("nazirul@example.com");
        assertThat(parsed.phone())
                .as("normalised to E.164 by libphonenumber, whatever the resume's formatting")
                .isEqualTo("+919876543210");
        assertThat(parsed.headline()).isEqualTo("Senior Software Engineer");
        assertThat(parsed.location()).isEqualTo("Bengaluru, India");
        assertThat(parsed.experiences()).hasSize(1);
        assertThat(parsed.experiences().get(0).getCompany()).isEqualTo("Acme");
        assertThat(parsed.educations().get(0).getInstitution()).isEqualTo("Anna University");
        assertThat(parsed.projects().get(0).getName()).isEqualTo("Toolpath viewer");
    }

    @Test
    void takesContactDetailsFromRegexEvenWhenTheModelInventsThem() {
        String replyWithInventedContact = """
                {"headline":"Engineer","location":null,"seniority":null,
                 "skills":[],"experiences":[],"educations":[],"projects":[],
                 "links":["wrong@example.com"]}
                """;

        ParsedResume parsed = parserWithModelReply(replyWithInventedContact).parse(ANY_CONTENT, PDF_TYPE);

        assertThat(parsed.email())
                .as("contact details must come from the resume text, never the model")
                .isEqualTo("nazirul@example.com");
    }

    @Test
    void mapsSeniorityToTheEnumRegardlessOfCasing() {
        assertThat(parserWithModelReply(FULL_EXTRACTION).parse(ANY_CONTENT, PDF_TYPE).seniority())
                .isEqualTo(SeniorityLevel.SENIOR);
    }

    @Test
    void leavesSeniorityUnsetWhenTheModelAnswersWithSomethingUnrecognised() {
        String reply = """
                {"headline":null,"location":null,"seniority":"Rockstar Ninja",
                 "skills":[],"experiences":[],"educations":[],"projects":[],"links":[]}
                """;

        assertThat(parserWithModelReply(reply).parse(ANY_CONTENT, PDF_TYPE).seniority()).isNull();
    }

    @Test
    void normalisesSkillsAndDeduplicatesThemCaseInsensitively() {
        ParsedResume parsed = parserWithModelReply(FULL_EXTRACTION).parse(ANY_CONTENT, PDF_TYPE);

        assertThat(parsed.skills())
                .as("'React' and '  react  ' are one skill; blanks are dropped")
                .contains("React", "GraphQL", "Java")
                .doesNotContain("react", "  react  ", "");
    }

    @Test
    void keepsFreeFormSkillsThatAreNotInTheDictionary() {
        assertThat(parserWithModelReply(FULL_EXTRACTION).parse(ANY_CONTENT, PDF_TYPE).skills())
                .as("GraphQL is absent from KNOWN_SKILLS and must survive anyway")
                .contains("GraphQL");
    }

    @Test
    void deduplicatesRepeatedLinks() {
        assertThat(parserWithModelReply(FULL_EXTRACTION).parse(ANY_CONTENT, PDF_TYPE).links())
                .containsExactly("https://github.com/example");
    }

    @Test
    void discardsLinkSuggestionsThatAreLabelsRatherThanAddresses() {
        String replyWithLabels = """
                {"headline":null,"location":null,"seniority":null,"skills":[],
                 "experiences":[],"educations":[],"projects":[],
                 "links":["Linkedin","GitHub","linkedin.com/in/example"]}
                """;

        assertThat(parserWithModelReply(replyWithLabels).parse(ANY_CONTENT, PDF_TYPE).links())
                .as("a label is useless as a profile link; only the address survives")
                .containsExactly("linkedin.com/in/example");
    }

    @Test
    void skipsTheModelEntirelyWhenTheTextIsTooShort() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(FULL_EXTRACTION);
        LlmResumeParser parser = parserFor("Nazirul Hashar\nnazirul@example.com\nJava", provider);

        ParsedResume parsed = parser.parse(ANY_CONTENT, PDF_TYPE);

        assertThat(provider.callCount()).as("low-signal text must not spend a request").isZero();
        assertThat(parsed.email()).isEqualTo("nazirul@example.com");
        assertThat(parsed.skills()).contains("Java");
        assertThat(parsed.experiences()).isEmpty();
    }

    @Test
    void skipsTheModelWhenTheTextIsGarbled() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(FULL_EXTRACTION);
        LlmResumeParser parser = parserFor("�".repeat(200), provider);

        parser.parse(ANY_CONTENT, PDF_TYPE);

        assertThat(provider.callCount()).isZero();
    }

    @Test
    void skipsTheModelWhenWordsRunTogetherAsInATwoColumnPdf() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(FULL_EXTRACTION);
        LlmResumeParser parser = parserFor("SeniorSoftwareEngineerJavaSpringMySQLDockerKubernetes".repeat(8), provider);

        parser.parse(ANY_CONTENT, PDF_TYPE);

        assertThat(provider.callCount()).isZero();
    }

    @Test
    void fallsBackToDeterministicSuggestionsWhenTheModelIsUnavailable() {
        ChatCompletionClient failing = FakeChatCompletionClient.failingWith(
                new LlmUnavailableException("Model did not respond in time", true));

        ParsedResume parsed = parserFor(READABLE_RESUME, failing).parse(ANY_CONTENT, PDF_TYPE);

        assertThat(parsed.email()).isEqualTo("nazirul@example.com");
        assertThat(parsed.skills()).contains("Java", "Spring", "MySQL", "Docker", "Kubernetes");
        assertThat(parsed.experiences()).isEmpty();
        assertThat(parsed.headline()).isNull();
    }

    @Test
    void fallsBackToDeterministicSuggestionsWhenAiIsDisabled() {
        ParsedResume parsed = parserFor(READABLE_RESUME, new DisabledChatClient()).parse(ANY_CONTENT, PDF_TYPE);

        assertThat(parsed.email()).isEqualTo("nazirul@example.com");
        assertThat(parsed.experiences()).isEmpty();
    }

    @Test
    void fallsBackToDeterministicSuggestionsWhenTheModelRepliesWithNonsense() {
        ParsedResume parsed = parserWithModelReply("no JSON here at all").parse(ANY_CONTENT, PDF_TYPE);

        assertThat(parsed.email()).isEqualTo("nazirul@example.com");
        assertThat(parsed.experiences()).isEmpty();
    }

    @Test
    void discardsTheWholeReplyWhenItCarriesUndeclaredFields() {
        String replyWithExtraKey = """
                {"headline":"Engineer","location":null,"seniority":null,"salaryExpectation":"20 LPA",
                 "skills":["Rust"],"experiences":[],"educations":[],"projects":[],"links":[]}
                """;

        ParsedResume parsed = parserWithModelReply(replyWithExtraKey).parse(ANY_CONTENT, PDF_TYPE);

        assertThat(parsed.headline()).as("partial application is not allowed").isNull();
        assertThat(parsed.skills()).doesNotContain("Rust");
    }

    @Test
    void yieldsNoSuggestionsWhenTheFileCannotBeReadAtAll() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(FULL_EXTRACTION);
        LlmResumeParser parser = parserFor(null, provider);

        ParsedResume parsed = parser.parse(ANY_CONTENT, "application/octet-stream");

        assertThat(provider.callCount()).isZero();
        assertThat(parsed.email()).isNull();
        assertThat(parsed.skills()).isEmpty();
        assertThat(parsed.experiences()).isEmpty();
    }

    private LlmResumeParser parserWithModelReply(String reply) {
        return parserFor(READABLE_RESUME, FakeChatCompletionClient.replyingWith(reply));
    }

    private LlmResumeParser parserFor(String extractedText, ChatCompletionClient chatCompletionClient) {
        ResumeTextExtractor extractor = new StubResumeTextExtractor(extractedText);
        return new LlmResumeParser(
                extractor,
                new DeterministicResumeParser(extractor),
                new ResumeExtractionTask(chatCompletionClient, new ObjectMapper(), VALIDATOR,
                        new AiTaskBudget(4_000)));
    }

    /**
     * Returns fixed text so the tests exercise parsing and merging rather than PDFBox and POI.
     * Real file formats are covered by the manual verification the story calls for.
     */
    private static final class StubResumeTextExtractor extends ResumeTextExtractor {

        private final String text;

        private StubResumeTextExtractor(String text) {
            this.text = text;
        }

        @Override
        public String extractText(byte[] content, String contentType) {
            return text;
        }
    }
}
