package com.meridian.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.backend.common.dto.ErrorBody;
import com.meridian.backend.common.dto.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException, ServletException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        
        ErrorBody errorBody = ErrorBody.builder()
            .code("forbidden")
            .message("Admin access required")
            .details(null)
            .build();
        
        ErrorResponse errorResponse = new ErrorResponse(errorBody);
        
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}