package com.meridian.backend.common.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class HealthResponse {
    private String status;
    private String environment;
    private Map<String, HealthDependency> dependencies;

    public static HealthResponseBuilder builder() {
        return new HealthResponseBuilder();
    }

    public static class HealthResponseBuilder {
        private String status;
        private String environment;
        private Map<String, HealthDependency> dependencies;

        public HealthResponseBuilder status(String status) { this.status = status; return this; }
        public HealthResponseBuilder environment(String environment) { this.environment = environment; return this; }
        public HealthResponseBuilder dependencies(Map<String, HealthDependency> dependencies) { this.dependencies = dependencies; return this; }
        public HealthResponse build() {
            HealthResponse response = new HealthResponse();
            response.status = status;
            response.environment = environment;
            response.dependencies = dependencies;
            return response;
        }
    }
}