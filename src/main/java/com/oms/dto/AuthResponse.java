package com.oms.dto;

import com.oms.entity.User;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    String username,
    String email,
    String role
) {
    public static AuthResponse of(User user, String accessToken, String refreshToken) {
        return new AuthResponse(
            accessToken,
            refreshToken,
            user.getUsername(),
            user.getEmail(),
            user.getRole().name()
        );
    }
}
