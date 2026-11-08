package com.campus.lab.auth.wechat;

import com.campus.lab.common.BusinessException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class WeChatHttpGateway implements WeChatGateway {
    private final RestClient client;
    private final WeChatProperties properties;

    public WeChatHttpGateway(RestClient wechatRestClient, WeChatProperties properties) {
        this.client = wechatRestClient;
        this.properties = properties;
    }

    @Override
    public String openidForCode(String code) {
        if (properties.appId().isBlank() || properties.appSecret().isBlank()) {
            throw unavailable();
        }
        try {
            CodeResponse response = client.get().uri(uri -> uri.scheme("https").host("api.weixin.qq.com")
                            .path("/sns/jscode2session")
                            .queryParam("appid", properties.appId())
                            .queryParam("secret", properties.appSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code").build())
                    .retrieve().body(CodeResponse.class);
            if (response != null && (response.errcode() == null || response.errcode() == 0)
                    && response.openid() != null && !response.openid().isBlank()) {
                return response.openid();
            }
            if (response != null && (response.errcode() == 40029 || response.errcode() == 40163 || response.errcode() == 40226)) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "微信登录凭证无效，请重试");
            }
            throw unavailable();
        } catch (ResourceAccessException exception) {
            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录暂不可用，请稍后重试");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CodeResponse(String openid, Integer errcode, String errmsg) {
    }
}
