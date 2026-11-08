package com.campus.lab.auth.dto;

public record LoginResponse(String token, UserView user) {
}
