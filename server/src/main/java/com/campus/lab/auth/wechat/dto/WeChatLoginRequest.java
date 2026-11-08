package com.campus.lab.auth.wechat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WeChatLoginRequest(@NotBlank @Size(max = 128) String code) { }
