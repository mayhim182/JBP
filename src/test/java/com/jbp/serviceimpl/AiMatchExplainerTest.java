package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.MatchFactorKind;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.MatchExplainer.MatchExplanation;
import com.jbp.service.MatchExplainer.MatchExplanationInput;
import com.jbp.service.MatchExplainer.SkillDemand;
import com.jbp.service.MatchScorer.MatchFactor;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 13.5 — the model writes prose, and only prose. */
class AiMatchExplainerTest {

    private static final String RULE_REASON = "skills 1/2; seniority match; remote; 3 roles";

    private final ChatCompletionClient chatCompletionClient = Mockito.mock(ChatCompletionClient.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private final AiMatchExplainer explainer = new AiMatchExplainer(
            chatCompletionClient, new ObjectMapper(), validator, new AiTaskBudget(3000));

    @Test
    void returnsTheModelsTwoSentencesWhenTheReplyIsUsable() {
        givenTheModelReplies("{\"summary\":\"Your SPA work reads close to this role. Seniority lines up.\"}");

        MatchExplanation explanation = explainer.explain(input("React", new SkillDemand(6, 10)));

        assertThat(explanation.summary())
                .isEqualTo("Your SPA work reads close to this role. Seniority lines up.");
        assertThat(explanation.generated()).isTrue();
    }

    @Test
    void fallsBackToTheRuleReasonWhenTheModelIsUnavailable() {
        Mockito.when(chatCompletionClient.complete(Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new LlmUnavailableException("AI features are disabled", false));

        MatchExplanation explanation = explainer.explain(input("React", new SkillDemand(6, 10)));

        assertThat(explanation.summary()).isEqualTo(RULE_REASON);
        assertThat(explanation.generated())
                .as("labelling deterministic wording as AI-written would be a lie in the one place "
                        + "design 20 is making a point about provenance")
                .isFalse();
    }

    @Test
    void keepsTheSuggestionEvenWhenTheProseFallsBack() {
        Mockito.when(chatCompletionClient.complete(Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new LlmUnavailableException("AI features are disabled", false));

        MatchExplanation explanation = explainer.explain(input("React", new SkillDemand(6, 10)));

        assertThat(explanation.actionText())
                .as("the suggestion was composed from data, so a model outage cannot take it away")
                .contains("Add React to your skills");
        assertThat(explanation.actionSkill()).isEqualTo("React");
    }

    @Test
    void statesHowManyStrongMatchesAlsoNameTheSkill() {
        givenTheModelReplies("{\"summary\":\"Close on the work itself. Seniority matches.\"}");

        assertThat(explainer.explain(input("React", new SkillDemand(6, 10))).actionText())
                .isEqualTo("Add React to your skills — it's named here and in 6 of your 10 "
                        + "strongest matches.");
    }

    @Test
    void dropsTheAggregateClaimRatherThanStatingZeroOfTen() {
        givenTheModelReplies("{\"summary\":\"Close on the work itself. Seniority matches.\"}");

        assertThat(explainer.explain(input("React", new SkillDemand(0, 10))).actionText())
                .as("\"named in 0 of your 10\" is an argument against the suggestion")
                .isEqualTo("Add React to your skills — this role names it and your profile doesn't.");
    }

    @Test
    void suggestsNothingWhenTheProfileAlreadyCoversTheRole() {
        givenTheModelReplies("{\"summary\":\"Everything this role asks for is on your profile.\"}");

        MatchExplanation explanation = explainer.explain(input(null, new SkillDemand(0, 10)));

        assertThat(explanation.actionText())
                .as("design 20's card is simply absent rather than showing an invented improvement")
                .isNull();
        assertThat(explanation.actionSkill()).isNull();
    }

    @Test
    void discardsASummaryLongerThanTheCardCanHold() {
        givenTheModelReplies("{\"summary\":\"" + "x".repeat(201) + "\"}");

        assertThat(explainer.explain(input("React", new SkillDemand(6, 10))).generated())
                .as("a reply that overflows the box is rejected rather than truncated mid-word")
                .isFalse();
    }

    @Test
    void discardsAReplyThatIsNotTheShapeAsked() {
        givenTheModelReplies("{\"explanation\":\"wrong field name\"}");

        assertThat(explainer.explain(input("React", new SkillDemand(6, 10))).generated()).isFalse();
    }

    @Test
    void neverSendsTheScoreOrAnyWeightToTheModel() {
        givenTheModelReplies("{\"summary\":\"Close on the work. Seniority matches.\"}");

        explainer.explain(input("React", new SkillDemand(6, 10)));

        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatCompletionClient).complete(Mockito.anyString(), userMessage.capture());
        assertThat(userMessage.getValue())
                .as("the surest way to stop a model quoting a number is not to send it one")
                .doesNotContain("65")
                .doesNotContain("weight");
    }

    @Test
    void describesFactorsByTheirHumanLabelRatherThanTheirEnumName() {
        givenTheModelReplies("{\"summary\":\"Close on the work. Seniority matches.\"}");

        explainer.explain(input("React", new SkillDemand(6, 10)));

        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatCompletionClient).complete(Mockito.anyString(), userMessage.capture());
        assertThat(userMessage.getValue()).contains("Role similarity").doesNotContain("SEMANTIC");
    }

    @Test
    void spendsNoRequestWhenThereIsNoBreakdownToReasonOver() {
        MatchExplanation explanation = explainer.explain(new MatchExplanationInput(
                1L, 2L, "v", "Senior Frontend Engineer", 65, RULE_REASON,
                List.of(), Set.of(), Set.of(), null, () -> new SkillDemand(0, 0)));

        assertThat(explanation.generated()).isFalse();
        Mockito.verifyNoInteractions(chatCompletionClient);
    }

    @Test
    void doesNotCountSkillDemandWhenThereIsNoSkillToSuggest() {
        givenTheModelReplies("{\"summary\":\"Close on the work. Seniority matches.\"}");
        Supplier countingSupplier = new Supplier();

        explainer.explain(new MatchExplanationInput(
                1L, 2L, "v", "Senior Frontend Engineer", 65, RULE_REASON,
                factors(), Set.of("react"), Set.of("react"), null, countingSupplier));

        assertThat(countingSupplier.calls)
                .as("no missing skill means nothing to say, so the scoring pass must not run")
                .isZero();
    }

    private void givenTheModelReplies(String json) {
        Mockito.when(chatCompletionClient.complete(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(json);
    }

    private MatchExplanationInput input(String missingSkill, SkillDemand demand) {
        return new MatchExplanationInput(
                1L, 2L, "abc123", "Senior Frontend Engineer", 65, RULE_REASON, factors(),
                Set.of("react", "typescript"), Set.of("typescript"), missingSkill, () -> demand);
    }

    private List<MatchFactor> factors() {
        return List.of(
                new MatchFactor(MatchFactorKind.SKILLS, 35, 50, "skills 1 of 2"),
                new MatchFactor(MatchFactorKind.SEMANTIC, 30, 80, "strong"));
    }

    /** Counts invocations, to prove the deferred aggregate really is deferred. */
    private static final class Supplier implements java.util.function.Supplier<SkillDemand> {

        private int calls;

        @Override
        public SkillDemand get() {
            calls++;
            return new SkillDemand(6, 10);
        }
    }
}
