package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.EducationDto;
import com.jbp.dto.ExperienceDto;
import com.jbp.dto.ProjectDto;
import com.jbp.service.ChatCompletionClient;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Asks the model to read resume text into the sections a candidate profile holds.
 *
 * <p>The whole task is a prompt, a response record and a fallback — everything else (timeout,
 * retry, rate limiting, truncation, strict parsing, validation, degrading to the fallback) comes
 * from {@link AbstractStructuredAiTask}.
 *
 * <p>The response record reuses {@link ExperienceDto}, {@link EducationDto} and
 * {@link ProjectDto}, so the model's answer needs no translation before a candidate can apply it,
 * and the prompt's field names stay in step with the profile automatically.
 *
 * <p>Contact details are deliberately absent: {@link DeterministicResumeParser} finds those by
 * regex, which is exact where a model would be merely plausible.
 */
@Service
public class ResumeExtractionTask extends AbstractStructuredAiTask<String, ResumeExtractionTask.ResumeExtraction> {

    private static final String SYSTEM_PROMPT = """
            You extract structured data from resume text for a job platform.

            Reply with only a JSON object, no markdown and no commentary, using exactly these keys:
            {
              "headline": string or null,
              "location": string or null,
              "seniority": one of INTERN, JUNIOR, MID, SENIOR, LEAD, PRINCIPAL, or null,
              "skills": array of strings,
              "experiences": [{"title":string,"company":string,"startDate":string,"endDate":string,"description":string}],
              "educations": [{"institution":string,"degree":string,"fieldOfStudy":string,"startYear":string,"endYear":string}],
              "projects": [{"name":string,"description":string,"link":string}],
              "links": array of strings
            }

            Rules:
            - Use no keys other than those listed. Extra keys cause the whole answer to be discarded.
            - Copy what the resume says. Never invent an employer, date, qualification or skill.
            - Use null for a value the resume does not state, and an empty array for a missing section.
            - Dates stay in the resume's own wording, for example "Mar 2021" or "2019".
            - Do not include email addresses or phone numbers.
            """;

    public ResumeExtractionTask(ChatCompletionClient chatCompletionClient,
                                ObjectMapper objectMapper,
                                Validator validator,
                                AiTaskBudget budget) {
        super(chatCompletionClient, objectMapper, validator, budget);
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected Class<ResumeExtraction> responseType() {
        return ResumeExtraction.class;
    }

    @Override
    protected ResumeExtraction fallback() {
        return ResumeExtraction.nothingExtracted();
    }

    /**
     * The model's answer. Every field is optional — a resume legitimately may have no projects —
     * so the size limits are the only constraints: they reject a reply that has clearly gone off
     * the rails rather than storing hundreds of hallucinated entries.
     *
     * <p>{@code seniority} arrives as text and is mapped to the enum by {@link LlmResumeParser},
     * so no free-form value reaches the rest of the application.
     */
    public record ResumeExtraction(String headline,
                                   String location,
                                   String seniority,
                                   @Size(max = 60) List<String> skills,
                                   @Size(max = 30) List<ExperienceDto> experiences,
                                   @Size(max = 20) List<EducationDto> educations,
                                   @Size(max = 30) List<ProjectDto> projects,
                                   @Size(max = 20) List<String> links) {

        public static ResumeExtraction nothingExtracted() {
            return new ResumeExtraction(null, null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
