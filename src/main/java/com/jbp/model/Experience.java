package com.jbp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Experience {

    private String title;

    private String company;

    // Free-text period (e.g. "2021-03" .. "2024-01" or "Present"); kept simple for the MVP.
    private String startDate;

    private String endDate;

    @Column(length = 2000)
    private String description;
}
