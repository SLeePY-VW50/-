package com.campus.lab.auth.dto;

import com.campus.lab.auth.entity.UserStatus;
import java.util.List;

public record UserView(
        Long userId,
        String account,
        String name,
        List<String> roles,
        UserStatus status) {
}
