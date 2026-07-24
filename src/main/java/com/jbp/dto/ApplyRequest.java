package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * One-click apply payload. Optional: only screening answers, if the job asks any.
 * No profile data is re-entered — the application is created from the candidate's account.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplyRequest {

    private List<ScreeningAnswerDto> answers;
}
