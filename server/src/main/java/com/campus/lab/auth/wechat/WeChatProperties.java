package com.campus.lab.auth.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.wechat")
public record WeChatProperties(String appId, String appSecret, String checkinQrSecret) {
}
