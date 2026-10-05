package com.meridian.backend.common.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class HealthDependency {
    private String status;
    private Long latencyMs;

    public static HealthDependencyBuilder builder() {
        return new HealthDependencyBuilder();
    }

    public static class HealthDependencyBuilder {
        private String status;
        private Long latencyMs;

        public HealthDependencyBuilder status(String status) { this.status = status; return this; }
        public HealthDependencyBuilder latencyMs(Long latencyMs) { this.latencyMs = latencyMs; return this; }
        public HealthDependency build() {
            HealthDependency dep = new HealthDependency();
            dep.status = status;
            dep.latencyMs = latencyMs;
            return dep;
        }
    }
}