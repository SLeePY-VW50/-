package com.campus.lab.auth.wechat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WeChatBindRequest(
        @NotBlank @Size(max = 256) String bindTicket,
        @NotBlank @Size(max = 32) String account,
        @NotBlank @Size(max = 128) String password) { }
