package com.meridian.backend.auth;

import com.meridian.backend.auth.dto.LoginRequest;
import com.meridian.backend.auth.dto.LoginResponse;
import com.meridian.backend.auth.dto.UserResponse;
import com.meridian.backend.exception.UnprocessableException;
import com.meridian.backend.exception.NotFoundException;
import com.meridian.backend.orders.entity.User;
import com.meridian.backend.orders.repository.UserRepository;
import com.meridian.backend.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public LoginResponse authenticate(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new NotFoundException("Invalid email or password", "invalid_credentials", null));

        if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
            throw new UnprocessableException("User has no password set", "no_password", null);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new NotFoundException("Invalid email or password", "invalid_credentials", null);
        }

        String accessToken = jwtTokenProvider.generateToken(String.valueOf(user.getId()));

        return LoginResponse.builder()
            .accessToken(accessToken)
            .tokenType("bearer")
            .user(mapToUserResponse(user))
            .build();
    }

    public UserResponse getCurrentUser(User user) {
        return mapToUserResponse(user);
    }

    private UserResponse mapToUserResponse(User user) {
        return UserResponse.builder()
            .id(user.getId())
            .name(user.getName())
            .email(user.getEmail())
            .role(user.getRole())
            .build();
    }
}