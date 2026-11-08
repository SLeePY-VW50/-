package com.campus.lab.auth.wechat.dto;

import com.campus.lab.auth.dto.LoginResponse;
import com.campus.lab.auth.dto.UserView;

public record WeChatLoginResponse(
        boolean bound, String token, UserView user, String bindTicket, Long expiresIn) {
    public static WeChatLoginResponse bound(LoginResponse login) {
        return new WeChatLoginResponse(true, login.token(), login.user(), null, null);
    }

    public static WeChatLoginResponse unbound(String ticket, long expiresIn) {
        return new WeChatLoginResponse(false, null, null, ticket, expiresIn);
    }
}
