package com.jbp.dto;

import com.jbp.model.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationStatusUpdateRequest {

    @NotNull(message = "Target status is required")
    private ApplicationStatus status;

    // Optional; shown to the candidate when the recruiter rejects with a reason.
    private String rejectionReason;
}
