package com.jbp.controller;

import com.jbp.config.AiCapabilities;
import com.jbp.dto.ClientConfigResponse;
import com.jbp.dto.ClientConfigResponse.AiFeatureFlags;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-owned configuration the client needs before it renders.
 *
 * <p><strong>Why this exists rather than a build-time environment variable.</strong> Whether AI is on
 * is server truth that can change without a frontend deploy. Baking it into the bundle would mean a
 * redeploy to toggle a feature — and worse, a bundle that disagrees with the backend it is talking
 * to. Story 14.1 is the first feature that must know the answer <em>before first paint</em>, because
 * its section is absent rather than disabled when its capability is off.
 *
 * <p><strong>Public on purpose.</strong> The response is booleans about which features exist, not
 * about any user, and a guest browsing a job needs the same gating a candidate does. Being public is
 * also what lets it be fetched once at app start rather than after sign-in.
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final AiCapabilities aiCapabilities;

    @GetMapping
    public ResponseEntity<ClientConfigResponse> getClientConfig() {
        // Not logged: called on every cold load, and it says nothing that changes between callers.
        return ResponseEntity.ok(ClientConfigResponse.builder()
                .ai(AiFeatureFlags.builder()
                        .interviewPrep(aiCapabilities.interviewPrep())
                        .matchExplanation(aiCapabilities.matchExplanation())
                        .jobDescription(aiCapabilities.jobDescription())
                        .screeningAnswerAssist(aiCapabilities.screeningAnswerAssist())
                        .applicantSummary(aiCapabilities.applicantSummary())
                        .build())
                .build());
    }
}
