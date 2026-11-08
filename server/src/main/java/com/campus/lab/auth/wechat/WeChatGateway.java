package com.campus.lab.auth.wechat;

public interface WeChatGateway {
    String openidForCode(String code);
}
