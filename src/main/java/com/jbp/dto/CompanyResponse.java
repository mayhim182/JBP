package com.jbp.dto;

import com.jbp.model.VerificationStatus;
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
public class CompanyResponse {

    private Long id;
    private String name;
    private String description;
    private String website;
    private String logo;
    private String location;
    private VerificationStatus status;

    // Convenience flag for clients: true when status == VERIFIED.
    private boolean verified;

    private Long ownerId;
}
