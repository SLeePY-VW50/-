package com.campus.lab.auth.dto;

import java.util.List;

public record InternalUserResponse(
        Long userId,
        String account,
        String name,
        List<String> roles) {

    public static InternalUserResponse from(UserView user) {
        return new InternalUserResponse(user.userId(), user.account(), user.name(), user.roles());
    }
}
