package com.campus.lab.auth.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.campus.lab.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WeChatHttpGatewayTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final WeChatHttpGateway gateway = new WeChatHttpGateway(
            builder.build(), new WeChatProperties("wx-test", "secret", "qr-secret"));

    @Test
    void returnsOnlyTheOpenidForAValidCode() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/sns/jscode2session")))
                .andExpect(queryParam("appid", "wx-test"))
                .andExpect(queryParam("secret", "secret"))
                .andExpect(queryParam("js_code", "temporary-code"))
                .andExpect(queryParam("grant_type", "authorization_code"))
                .andRespond(withSuccess(
                        "{\"openid\":\"openid-1\",\"session_key\":\"do-not-store\"}",
                        MediaType.APPLICATION_JSON));
        assertThat(gateway.openidForCode("temporary-code")).isEqualTo("openid-1");
        server.verify();
    }

    @Test
    void usedCodeIsAnInvalidCredential() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/sns/jscode2session")))
                .andRespond(withSuccess("{\"errcode\":40163,\"errmsg\":\"code been used\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.openidForCode("used-code"))
                .isInstanceOf(BusinessException.class)
                .matches(error -> ((BusinessException) error).getStatus()
                        == org.springframework.http.HttpStatus.UNAUTHORIZED)
                .hasMessage("微信登录凭证无效，请重试");
        server.verify();
    }

    @Test
    void invalidCodeAndPlatformFailureHaveSafeErrors() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/sns/jscode2session")))
                .andRespond(withSuccess("{\"errcode\":40029,\"errmsg\":\"invalid code\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/sns/jscode2session")))
                .andRespond(withSuccess("{\"errcode\":-1,\"errmsg\":\"busy\"}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.openidForCode("bad-code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信登录凭证无效，请重试");
        assertThatThrownBy(() -> gateway.openidForCode("busy-code"))
                .isInstanceOf(BusinessException.class)
                .matches(error -> ((BusinessException) error).getStatus()
                        == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .hasMessage("微信登录暂不可用，请稍后重试");
        server.verify();
    }
}
