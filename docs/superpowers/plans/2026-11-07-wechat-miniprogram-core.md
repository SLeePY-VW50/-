# 微信小程序核心预约闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可在微信开发者工具中运行、连接真实 Spring Boot 后端的学生登录、预约、取消、扫码签到与签退闭环。

**Architecture:** 后端新增微信 code 换 openid、一次性绑定凭证和签名二维码入口，预约与签到规则继续由现有服务负责。小程序采用原生 TypeScript，以独立请求层、认证存储和纯时间片逻辑支撑原生页面，不引入跨端框架或 UI 库。

**Tech Stack:** JDK 21、Spring Boot 3.5.16、Spring Security、Spring Data Redis、MyBatis-Plus、JUnit 5、MockMvc；微信原生小程序、TypeScript 5.7.2、miniprogram-api-typings 5.3.1、Vitest 3.2.7。

---

## 执行约定与文件地图

- 设计依据：`docs/superpowers/specs/2026-11-07-wechat-miniprogram-core-design.md`。
- 微信官方参考：TypeScript 编译 `https://developers.weixin.qq.com/miniprogram/dev/devtools/compilets.html`；code2Session `https://developers.weixin.qq.com/miniprogram/dev/server/API/user-login/api_code2session.html`；`wx.scanCode` `https://developers.weixin.qq.com/miniprogram/dev/api/device/scan/wx.scanCode.html`。
- 后端命令在 `server/` 执行，小程序命令在 `miniprogram/` 执行。测试依赖本机 MySQL 3307 与 Redis 6379；测试使用独立数据库和 Redis database 15。
- 当前 `E:/yyy` 不是 Git 仓库。以下提交节点用于隔离每组变更；只有用户明确初始化/提供仓库并授权提交后才执行，否则保留变更、记录未提交原因，绝不擅自 `git init`。
- 不读取、打印或提交真实 AppSecret、二维码密钥、数据库密码。

主要新增文件：

```text
server/src/main/java/com/campus/lab/auth/wechat/
  WeChatProperties.java          微信与二维码服务端配置
  WeChatConfiguration.java       带超时的微信 RestClient
  WeChatGateway.java             可测试的微信平台边界
  WeChatHttpGateway.java         code2Session HTTP 实现
  WeChatAuthService.java         登录、一次性绑定凭证与唯一绑定
  dto/WeChatLoginRequest.java、WeChatBindRequest.java、WeChatLoginResponse.java
server/src/main/java/com/campus/lab/checkin/
  CheckinChannel.java、CheckoutChannel.java
  qr/CheckinQrService.java       签到码签名、解析与验签
  qr/QrCheckinService.java       锁与统一签到事务编排
  qr/dto/QrCheckinRequest.java、QrCheckinView.java、CheckinCodeView.java
  dto/CheckoutReservationRequest.java
miniprogram/
  project.config.json            原生 TS 开发者工具工程
  package.json、tsconfig.json、vitest.config.ts
  miniprogram/api/               请求、类型与业务接口
  miniprogram/auth/              本地身份与登录流程
  miniprogram/config/            本地 API 地址和开发入口判定
  miniprogram/utils/             日期、状态、时间片与预约表单纯逻辑
  miniprogram/actions/           扫码签到等跨页面动作
  miniprogram/components/slot-grid/
  miniprogram/pages/             login、bind、home、labs、profile、reservation-form、reservations、reservation-detail
  tests/                         Node 环境下的纯逻辑与 wx API mock 测试
```

### Task 1: 微信服务端配置与 code2Session 网关

**Files:**
- Create: `server/src/main/java/com/campus/lab/auth/wechat/WeChatProperties.java`
- Create: `server/src/main/java/com/campus/lab/auth/wechat/WeChatConfiguration.java`
- Create: `server/src/main/java/com/campus/lab/auth/wechat/WeChatGateway.java`
- Create: `server/src/main/java/com/campus/lab/auth/wechat/WeChatHttpGateway.java`
- Test: `server/src/test/java/com/campus/lab/auth/wechat/WeChatHttpGatewayTest.java`
- Modify: `server/src/main/resources/application.yml`
- Modify: `server/src/main/resources/application-local.yml`
- Modify: `server/src/main/resources/application-prod.yml`
- Modify: `server/src/test/resources/application-test.yml`
- Modify: `server/src/test/java/com/campus/lab/ConfigurationProfileTest.java`
- Modify: `scripts/dev-env.ps1`

- [ ] **Step 1: 写网关失败测试**

```java
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
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"))
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
    void invalidCodeAndPlatformFailureHaveSafeErrors() {
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"))
                .andRespond(withSuccess("{\"errcode\":40029,\"errmsg\":\"invalid code\"}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.openidForCode("bad-code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信登录凭证无效，请重试");
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"))
                .andRespond(withSuccess("{\"errcode\":-1,\"errmsg\":\"busy\"}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.openidForCode("busy-code"))
                .isInstanceOf(BusinessException.class)
                .matches(error -> ((BusinessException) error).getStatus()
                        == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .hasMessage("微信登录暂不可用，请稍后重试");
        server.verify();
    }
}
```

- [ ] **Step 2: 运行测试并确认因类不存在而失败**

Run: `mvn -Dtest=WeChatHttpGatewayTest test`
Expected: COMPILATION ERROR，找不到 `WeChatHttpGateway` 与 `WeChatProperties`。

- [ ] **Step 3: 实现配置和最小 HTTP 网关**

`WeChatProperties.java`：

```java
package com.campus.lab.auth.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.wechat")
public record WeChatProperties(String appId, String appSecret, String checkinQrSecret) {
}
```

`WeChatConfiguration.java`：

```java
package com.campus.lab.auth.wechat;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(WeChatProperties.class)
public class WeChatConfiguration {
    @Bean
    RestClient wechatRestClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return builder.requestFactory(factory).baseUrl("https://api.weixin.qq.com").build();
    }
}
```

`WeChatGateway.java`：

```java
package com.campus.lab.auth.wechat;

public interface WeChatGateway {
    String openidForCode(String code);
}
```

`WeChatHttpGateway.java`：

```java
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
            CodeResponse response = client.get().uri(uri -> uri.path("/sns/jscode2session")
                            .queryParam("appid", properties.appId())
                            .queryParam("secret", properties.appSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code").build())
                    .retrieve().body(CodeResponse.class);
            if (response != null && (response.errcode() == null || response.errcode() == 0)
                    && response.openid() != null && !response.openid().isBlank()) {
                return response.openid();
            }
            if (response != null && (response.errcode() == 40029 || response.errcode() == 40226)) {
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
```

- [ ] **Step 4: 运行网关测试**

Run: `mvn -Dtest=WeChatHttpGatewayTest test`
Expected: `Tests run: 2, Failures: 0, Errors: 0`。

- [ ] **Step 5: 添加 profile 配置失败断言**

在 `ConfigurationProfileTest` 新增：

```java
@Test
void wechatSecretsComeFromEnvironmentWithoutProductionDefaults() throws Exception {
    var yaml = new org.yaml.snakeyaml.Yaml();
    try (var input = new org.springframework.core.io.ClassPathResource("application-prod.yml")
            .getInputStream()) {
        java.util.Map<String, Object> root = yaml.load(input);
        var app = (java.util.Map<String, Object>) root.get("app");
        var wechat = (java.util.Map<String, Object>) app.get("wechat");
        assertThat(wechat).containsEntry("app-id", "${WECHAT_APP_ID:}")
                .containsEntry("app-secret", "${WECHAT_APP_SECRET:}")
                .containsEntry("checkin-qr-secret", "${WECHAT_CHECKIN_QR_SECRET:}");
    }
}
```

Run: `mvn -Dtest=ConfigurationProfileTest#wechatSecretsComeFromEnvironmentWithoutProductionDefaults test`
Expected: FAIL，`app.wechat` 节点不存在。

- [ ] **Step 6: 写入非秘密配置与本地专用测试值**

在三个 application 文件的 `app:` 下加入相同结构，值分别如下；`application-test.yml` 使用测试值，`application-local.yml` 仅给二维码密钥本地默认值：

```yaml
# application.yml 与 application-prod.yml
  wechat:
    app-id: ${WECHAT_APP_ID:}
    app-secret: ${WECHAT_APP_SECRET:}
    checkin-qr-secret: ${WECHAT_CHECKIN_QR_SECRET:}
```

```yaml
# application-local.yml
  wechat:
    app-id: ${WECHAT_APP_ID:}
    app-secret: ${WECHAT_APP_SECRET:}
    checkin-qr-secret: ${WECHAT_CHECKIN_QR_SECRET:local-only-checkin-qr-secret-32-bytes}
```

```yaml
# application-test.yml
  wechat:
    app-id: wx-test-only
    app-secret: test-only-wechat-secret
    checkin-qr-secret: test-only-checkin-qr-secret-with-32-bytes
```

在 `scripts/dev-env.ps1` 的现有 secret 导入语句后加入：

```powershell
Import-UserSecret 'WECHAT_APP_ID'
Import-UserSecret 'WECHAT_APP_SECRET'
Import-UserSecret 'WECHAT_CHECKIN_QR_SECRET'
```

Run: `mvn -Dtest=WeChatHttpGatewayTest,ConfigurationProfileTest test`
Expected: PASS。

- [ ] **Step 7: 提交本任务（仅在仓库与授权存在时）**

```bash
git add server/src/main/java/com/campus/lab/auth/wechat server/src/main/resources/application*.yml server/src/test/java/com/campus/lab/auth/wechat server/src/test/java/com/campus/lab/ConfigurationProfileTest.java server/src/test/resources/application-test.yml scripts/dev-env.ps1
git commit -m "feat: add WeChat login gateway configuration"
```

### Task 2: 微信登录、一次性绑定凭证与双向唯一绑定

**Files:**
- Create: `server/src/main/java/com/campus/lab/auth/wechat/WeChatAuthService.java`
- Create: `server/src/main/java/com/campus/lab/auth/wechat/dto/WeChatLoginRequest.java`
- Create: `server/src/main/java/com/campus/lab/auth/wechat/dto/WeChatBindRequest.java`
- Create: `server/src/main/java/com/campus/lab/auth/wechat/dto/WeChatLoginResponse.java`
- Modify: `server/src/main/java/com/campus/lab/auth/AuthService.java`
- Modify: `server/src/main/java/com/campus/lab/auth/AuthController.java`
- Modify: `server/src/main/java/com/campus/lab/auth/SecurityConfig.java`
- Modify: `server/src/main/java/com/campus/lab/auth/mapper/UserMapper.java`
- Test: `server/src/test/java/com/campus/lab/WeChatAuthIntegrationTest.java`

- [ ] **Step 1: 写登录、绑定、限次和复用失败测试**

```java
package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.auth.wechat.WeChatGateway;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

class WeChatAuthIntegrationTest extends AbstractIntegrationTest {
    @MockitoBean private WeChatGateway gateway;
    @Autowired private StringRedisTemplate redis;
    private final List<String> tickets = new ArrayList<>();

    @AfterEach
    void removeTickets() {
        for (String ticket : tickets) {
            redis.delete("auth:wechat:bind:" + ticket);
            redis.delete("auth:wechat:bind-fail:" + ticket);
        }
    }

    @Test
    void boundOpenidLogsInAndUnboundOpenidMustBindBeforeReceivingJwt() throws Exception {
        String account = createTestAccount("STUDENT");
        String openid = "openid-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE sys_user SET wechat_openid = ? WHERE account = ?", openid, account);
        when(gateway.openidForCode("bound-code")).thenReturn(openid);
        mockMvc.perform(post("/api/auth/wechat/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "bound-code"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.bound").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.account").value(account))
                .andExpect(jsonPath("$.data.openid").doesNotExist());

        String unboundOpenid = "openid-" + UUID.randomUUID();
        when(gateway.openidForCode("new-code")).thenReturn(unboundOpenid);
        JsonNode unbound = response(post("/api/auth/wechat/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", "new-code"))));
        assertThat(unbound.path("data").path("bound").asBoolean()).isFalse();
        assertThat(unbound.path("data").path("token").isNull()).isTrue();
        String ticket = unbound.path("data").path("bindTicket").asText();
        tickets.add(ticket);
        assertThat(redis.opsForValue().get("auth:wechat:bind:" + ticket)).isEqualTo(unboundOpenid);

        mockMvc.perform(post("/api/auth/wechat/bind").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bindTicket", ticket, "account", account, "password", "Lab@2026"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").isNotEmpty());
        assertThat(redis.hasKey("auth:wechat:bind:" + ticket)).isFalse();
        mockMvc.perform(post("/api/auth/wechat/bind").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bindTicket", ticket, "account", account, "password", "Lab@2026"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fiveWrongPasswordsInvalidateTicketAndDuplicateOpenidIsRejected() throws Exception {
        String first = createTestAccount("STUDENT");
        String second = createTestAccount("STUDENT");
        String openid = "openid-" + UUID.randomUUID();
        when(gateway.openidForCode("limited-code")).thenReturn(openid);
        String ticket = ticket("limited-code");
        for (int attempt = 0; attempt < 4; attempt++) {
            bind(ticket, first, "wrong-password").andExpect(status().isUnauthorized());
        }
        bind(ticket, first, "wrong-password").andExpect(status().isUnauthorized());
        bind(ticket, first, "Lab@2026").andExpect(status().isUnauthorized());

        jdbcTemplate.update("UPDATE sys_user SET wechat_openid = ? WHERE account = ?", openid, first);
        when(gateway.openidForCode("duplicate-code")).thenReturn(openid);
        String duplicateTicket = ticket("duplicate-code");
        bind(duplicateTicket, second, "Lab@2026").andExpect(status().isConflict());
    }

    @Test
    void disabledAccountExpiredTicketAndAlreadyBoundAccountAreRejected() throws Exception {
        String disabled = createTestAccount("STUDENT");
        jdbcTemplate.update("UPDATE sys_user SET status = 'DISABLED' WHERE account = ?", disabled);
        String boundOpenid = "openid-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE sys_user SET wechat_openid = ? WHERE account = ?", boundOpenid, disabled);
        when(gateway.openidForCode("disabled-code")).thenReturn(boundOpenid);
        mockMvc.perform(post("/api/auth/wechat/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "disabled-code"))))
                .andExpect(status().isUnauthorized());

        String active = createTestAccount("STUDENT");
        when(gateway.openidForCode("expired-code")).thenReturn("openid-" + UUID.randomUUID());
        String expired = ticket("expired-code");
        redis.expire("auth:wechat:bind:" + expired, java.time.Duration.ofMillis(-1));
        bind(expired, active, "Lab@2026").andExpect(status().isUnauthorized());

        jdbcTemplate.update("UPDATE sys_user SET wechat_openid = ? WHERE account = ?",
                "openid-existing-" + UUID.randomUUID(), active);
        when(gateway.openidForCode("other-openid-code")).thenReturn("openid-new-" + UUID.randomUUID());
        String otherOpenid = ticket("other-openid-code");
        bind(otherOpenid, active, "Lab@2026").andExpect(status().isConflict());
    }

    private String ticket(String code) throws Exception {
        JsonNode body = response(post("/api/auth/wechat/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", code))));
        String ticket = body.path("data").path("bindTicket").asText();
        tickets.add(ticket);
        return ticket;
    }

    private org.springframework.test.web.servlet.ResultActions bind(
            String ticket, String account, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/wechat/bind").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "bindTicket", ticket, "account", account, "password", password))));
    }

    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
```

- [ ] **Step 2: 运行测试并确认 404/编译失败**

Run: `mvn -Dtest=WeChatAuthIntegrationTest test`
Expected: COMPILATION ERROR，微信 DTO 与网关实现尚不完整。

- [ ] **Step 3: 抽取现有账号认证并增加条件绑定 SQL**

将 `AuthService.login` 改为复用以下两个公开服务方法，保留原有错误文案与 BCrypt 占位校验：

```java
public LoginResponse login(LoginRequest request) {
    return loginForUser(userByCredentials(request.account(), request.password()));
}

public UserEntity userByCredentials(String account, String password) {
    UserEntity user = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
            .eq(UserEntity::getAccount, account));
    if (user == null) {
        passwordEncoder.matches(password, unavailableAccountHash);
        throw invalidCredentials();
    }
    if (!passwordEncoder.matches(password, user.getPasswordHash())
            || user.getStatus() != UserStatus.ACTIVE) {
        throw invalidCredentials();
    }
    return user;
}

public LoginResponse loginForUser(UserEntity user) {
    if (user.getStatus() != UserStatus.ACTIVE) {
        throw invalidCredentials();
    }
    UserView view = toView(user);
    return new LoginResponse(jwtService.issueToken(view), view);
}
```

在 `UserMapper` 增加：

```java
@org.apache.ibatis.annotations.Update("""
        UPDATE sys_user
        SET wechat_openid = #{openid}, wechat_bind_time = #{bindTime}
        WHERE user_id = #{userId} AND wechat_openid IS NULL
        """)
int bindWechat(@Param("userId") long userId, @Param("openid") String openid,
               @Param("bindTime") java.time.LocalDateTime bindTime);
```

- [ ] **Step 4: 实现 DTO 与绑定服务**

```java
// WeChatLoginRequest.java
package com.campus.lab.auth.wechat.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record WeChatLoginRequest(@NotBlank @Size(max = 128) String code) { }
```

```java
// WeChatBindRequest.java
package com.campus.lab.auth.wechat.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record WeChatBindRequest(
        @NotBlank @Size(max = 256) String bindTicket,
        @NotBlank @Size(max = 32) String account,
        @NotBlank @Size(max = 128) String password) { }
```

```java
// WeChatLoginResponse.java
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
```

`WeChatAuthService.java`：

```java
package com.campus.lab.auth.wechat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.lab.auth.AuthService;
import com.campus.lab.auth.dto.LoginResponse;
import com.campus.lab.auth.entity.UserEntity;
import com.campus.lab.auth.mapper.UserMapper;
import com.campus.lab.auth.wechat.dto.WeChatBindRequest;
import com.campus.lab.auth.wechat.dto.WeChatLoginResponse;
import com.campus.lab.common.BusinessException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeChatAuthService {
    private static final Duration TICKET_TTL = Duration.ofMinutes(5);
    private static final int MAX_FAILURES = 5;
    private final WeChatGateway gateway;
    private final UserMapper users;
    private final AuthService auth;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WeChatAuthService(WeChatGateway gateway, UserMapper users, AuthService auth,
            StringRedisTemplate redis, Clock clock) {
        this.gateway = gateway;
        this.users = users;
        this.auth = auth;
        this.redis = redis;
        this.clock = clock;
    }

    public WeChatLoginResponse login(String code) {
        String openid = gateway.openidForCode(code);
        UserEntity user = users.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getWechatOpenid, openid));
        if (user != null) return WeChatLoginResponse.bound(auth.loginForUser(user));
        String ticket = newTicket();
        redis.opsForValue().set(ticketKey(ticket), openid, TICKET_TTL);
        return WeChatLoginResponse.unbound(ticket, TICKET_TTL.toSeconds());
    }

    @Transactional(rollbackFor = Exception.class)
    public LoginResponse bind(WeChatBindRequest request) {
        String key = ticketKey(request.bindTicket());
        String openid = redis.opsForValue().get(key);
        if (openid == null) throw invalidTicket();
        UserEntity user;
        try {
            user = auth.userByCredentials(request.account(), request.password());
        } catch (BusinessException exception) {
            registerFailure(request.bindTicket(), key);
            throw exception;
        }
        if (user.getWechatOpenid() != null) throw alreadyBound();
        UserEntity other = users.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getWechatOpenid, openid));
        if (other != null) throw alreadyBound();
        try {
            if (users.bindWechat(user.getUserId(), openid, LocalDateTime.now(clock)) != 1) {
                throw alreadyBound();
            }
        } catch (DuplicateKeyException exception) {
            throw alreadyBound();
        }
        redis.delete(key);
        redis.delete(failureKey(request.bindTicket()));
        return auth.loginForUser(users.selectById(user.getUserId()));
    }

    private void registerFailure(String ticket, String key) {
        String failureKey = failureKey(ticket);
        Long failures = redis.opsForValue().increment(failureKey);
        redis.expire(failureKey, TICKET_TTL);
        if (failures != null && failures >= MAX_FAILURES) {
            redis.delete(key);
            redis.delete(failureKey);
        }
    }

    private String newTicket() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String ticketKey(String ticket) { return "auth:wechat:bind:" + ticket; }
    private static String failureKey(String ticket) { return "auth:wechat:bind-fail:" + ticket; }
    private static BusinessException invalidTicket() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "微信绑定凭证无效或已过期，请重新登录");
    }
    private static BusinessException alreadyBound() {
        return new BusinessException(HttpStatus.CONFLICT, "该学号或微信已绑定其他账号");
    }
}
```

- [ ] **Step 5: 暴露接口并只放行两个微信认证入口**

在 `AuthController` 注入 `WeChatAuthService`，新增：

```java
@PostMapping("/auth/wechat/login")
public ApiResponse<com.campus.lab.auth.wechat.dto.WeChatLoginResponse> wechatLogin(
        @Valid @RequestBody com.campus.lab.auth.wechat.dto.WeChatLoginRequest request) {
    return ApiResponse.success(wechatAuthService.login(request.code()));
}

@PostMapping("/auth/wechat/bind")
public ApiResponse<LoginResponse> wechatBind(
        @Valid @RequestBody com.campus.lab.auth.wechat.dto.WeChatBindRequest request) {
    return ApiResponse.success(wechatAuthService.bind(request));
}
```

在 `SecurityConfig` 的 `/api/auth/login` 放行规则旁加入：

```java
.requestMatchers(HttpMethod.POST, "/api/auth/wechat/login", "/api/auth/wechat/bind").permitAll()
```

- [ ] **Step 6: 运行认证新增与回归测试**

Run: `mvn -Dtest=WeChatAuthIntegrationTest,AuthIntegrationTest test`
Expected: PASS；响应和日志不包含 openid、密码或 AppSecret。

- [ ] **Step 7: 提交本任务（仅在仓库与授权存在时）**

```bash
git add server/src/main/java/com/campus/lab/auth server/src/test/java/com/campus/lab/WeChatAuthIntegrationTest.java
git commit -m "feat: bind WeChat identity to campus accounts"
```

### Task 3: 签名二维码与统一签到渠道

**Files:**
- Create: `server/src/main/java/com/campus/lab/checkin/CheckinChannel.java`
- Create: `server/src/main/java/com/campus/lab/checkin/qr/CheckinQrService.java`
- Create: `server/src/main/java/com/campus/lab/checkin/qr/QrCheckinService.java`
- Create: `server/src/main/java/com/campus/lab/checkin/qr/dto/QrCheckinRequest.java`
- Create: `server/src/main/java/com/campus/lab/checkin/qr/dto/QrCheckinView.java`
- Create: `server/src/main/java/com/campus/lab/checkin/qr/dto/CheckinCodeView.java`
- Modify: `server/src/main/java/com/campus/lab/checkin/CheckinController.java`
- Modify: `server/src/main/java/com/campus/lab/checkin/ReservationCheckinTransactionService.java`
- Modify: `server/src/main/java/com/campus/lab/checkin/TerminalCheckinService.java`
- Modify: `server/src/main/java/com/campus/lab/reservation/ReservationRepository.java:320-334`
- Modify: `server/src/main/java/com/campus/lab/resource/AdminResourceController.java`
- Modify: `server/src/main/java/com/campus/lab/resource/ResourceService.java:142-151`
- Test: `server/src/test/java/com/campus/lab/checkin/qr/CheckinQrServiceTest.java`
- Test: `server/src/test/java/com/campus/lab/QrCheckinIntegrationTest.java`

- [ ] **Step 1: 写签名与验签失败测试**

```java
package com.campus.lab.checkin.qr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.lab.auth.wechat.WeChatProperties;
import com.campus.lab.common.BusinessException;
import org.junit.jupiter.api.Test;

class CheckinQrServiceTest {
    private final CheckinQrService service = new CheckinQrService(
            new WeChatProperties("wx", "secret", "test-only-checkin-qr-secret-with-32-bytes"));

    @Test
    void signedCodeRoundTripsAndTamperingIsRejected() {
        String content = service.contentForLab(42);
        assertThat(content).startsWith("labspace://checkin?v=1&labId=42&signature=");
        assertThat(service.verify(content)).isEqualTo(42);
        assertThatThrownBy(() -> service.verify(content.replace("labId=42", "labId=41")))
                .isInstanceOf(BusinessException.class).hasMessage("二维码无效");
        assertThatThrownBy(() -> service.verify(content + "&extra=1"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.verify("https://example.com/checkin"))
                .isInstanceOf(BusinessException.class);
    }
}
```

Run: `mvn -Dtest=CheckinQrServiceTest test`
Expected: COMPILATION ERROR。

- [ ] **Step 2: 实现规范签名、严格解析和常量时间比较**

```java
package com.campus.lab.checkin.qr;

import com.campus.lab.auth.wechat.WeChatProperties;
import com.campus.lab.common.BusinessException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CheckinQrService {
    private final WeChatProperties properties;
    public CheckinQrService(WeChatProperties properties) { this.properties = properties; }

    public String contentForLab(long labId) {
        if (labId <= 0) throw invalid();
        return "labspace://checkin?v=1&labId=" + labId + "&signature=" + signature(labId);
    }

    public long verify(String content) {
        try {
            URI uri = URI.create(content);
            if (!"labspace".equals(uri.getScheme()) || !"checkin".equals(uri.getHost())) throw invalid();
            Map<String, String> values = new HashMap<>();
            for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2 || values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) throw invalid();
            }
            if (!values.keySet().equals(java.util.Set.of("v", "labId", "signature"))
                    || !"1".equals(values.get("v"))) throw invalid();
            long labId = Long.parseLong(values.get("labId"));
            byte[] expected = Base64.getUrlDecoder().decode(values.get("signature"));
            byte[] actual = Base64.getUrlDecoder().decode(signature(labId));
            if (!MessageDigest.isEqual(expected, actual)) throw invalid();
            return labId;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid();
        }
    }

    private String signature(long labId) {
        try {
            byte[] secret = properties.checkinQrSecret().getBytes(StandardCharsets.UTF_8);
            if (secret.length < 32) throw new IllegalStateException("签到二维码密钥未配置");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(("v=1&labId=" + labId).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("签到二维码签名不可用", exception);
        }
    }

    public static BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "二维码无效");
    }
}
```

Run: `mvn -Dtest=CheckinQrServiceTest test`
Expected: PASS。

- [ ] **Step 3: 写扫码签到失败集成测试**

创建完整 `QrCheckinIntegrationTest`：

```java
package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.checkin.qr.CheckinQrService;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class QrCheckinIntegrationTest extends AbstractCheckinNonTransactionalTest {
    @Autowired private CheckinQrService qrService;

    @Test
    void validCodeChecksInOnceAndWrongLaboratoryOrSignatureDoesNotChangeState() throws Exception {
        long labId = createLaboratory(2);
        long otherLabId = createLaboratory(2);
        String account = createTestAccount("STUDENT");
        String token = loginAndGetToken(account, USER_PASSWORD);
        long reservationId = createReservation(userIdByAccount(account), labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 10, 0), true);

        MvcResult first = qr(token, reservationId, qrService.contentForLab(labId)).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(first).path("data").path("alreadyCheckedIn").asBoolean()).isFalse();
        assertThat(responseJson(first).path("data").path("reservation")
                .path("checkinChannel").asText()).isEqualTo("WECHAT_QR");
        MvcResult repeated = qr(token, reservationId, qrService.contentForLab(labId)).andReturn();
        assertThat(responseJson(repeated).path("data").path("alreadyCheckedIn").asBoolean()).isTrue();
        assertThat(checkinLogCount(reservationId)).isEqualTo(1);

        assertThat(qr(token, reservationId, qrService.contentForLab(otherLabId)).andReturn()
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(qr(token, reservationId, "labspace://checkin?v=1&labId=" + labId
                + "&signature=invalid").andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(statusOf(reservationId)).isEqualTo("IN_USE");
    }

    @Test
    void nonOwnerOutsideWindowAndAnonymousRequestsCannotChangeReservation() throws Exception {
        long labId = createLaboratory(2);
        String owner = createTestAccount("STUDENT");
        String other = createTestAccount("STUDENT");
        String ownerToken = loginAndGetToken(owner, USER_PASSWORD);
        String otherToken = loginAndGetToken(other, USER_PASSWORD);
        long reservationId = createReservation(userIdByAccount(owner), labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 10, 0), false);
        String content = qrService.contentForLab(labId);

        assertThat(qr(otherToken, reservationId, content).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        mockMvc.perform(post("/api/checkin/qr").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reservationId", reservationId, "content", content))))
                .andExpect(status().isUnauthorized());
        jdbcTemplate.update("UPDATE t_reservation SET start_time = '2026-10-24 10:00:00', "
                + "end_time = '2026-10-24 11:00:00' WHERE resv_id = ?", reservationId);
        assertThat(qr(ownerToken, reservationId, content).andReturn().getResponse().getStatus())
                .isEqualTo(409);
        assertThat(statusOf(reservationId)).isEqualTo("APPROVED");
        assertThat(checkinLogCount(reservationId)).isZero();
    }

    @Test
    void onlySystemAdministratorCanReadCheckinCode() throws Exception {
        long labId = createLaboratory(1);
        String admin = createTestAccount("SYS_ADMIN");
        String student = createTestAccount("STUDENT");
        mockMvc.perform(get("/api/admin/resources/labs/{labId}/checkin-code", labId)
                        .header("Authorization", "Bearer " + loginAndGetToken(admin, USER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labId").value(labId))
                .andExpect(jsonPath("$.data.content").isNotEmpty());
        mockMvc.perform(get("/api/admin/resources/labs/{labId}/checkin-code", labId)
                        .header("Authorization", "Bearer " + loginAndGetToken(student, USER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions qr(
            String token, long reservationId, String content) throws Exception {
        return mockMvc.perform(post("/api/checkin/qr").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "reservationId", reservationId, "content", content))));
    }

    private String statusOf(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId);
    }

    private int checkinLogCount(long reservationId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_reservation_log "
                + "WHERE resv_id = ? AND from_status = 'APPROVED' AND to_status = 'IN_USE'",
                Integer.class, reservationId);
    }
}
```

Run: `mvn -Dtest=QrCheckinIntegrationTest test`
Expected: 404 或编译失败。

- [ ] **Step 4: 泛化既有签到事务，不改变终端调用语义**

```java
// CheckinChannel.java
package com.campus.lab.checkin;
public enum CheckinChannel {
    TERMINAL_ACCOUNT("公共终端账号签到"), WECHAT_QR("微信扫码签到");
    private final String logReason;
    CheckinChannel(String logReason) { this.logReason = logReason; }
    public String logReason() { return logReason; }
}
```

将 `ReservationCheckinTransactionService.checkin` 签名改为：

```java
public CheckinOutcome checkin(long reservationId, long userId, long laboratoryLabId,
        LabLockKey acquiredLock, CheckinChannel channel)
```

把事务内两行更新改为 `repository.checkinReservation(reservationId, userId, terminalLabId, now, channel)` 与 `repository.insertCheckinLog(reservationId, userId, channel)`；不要复制事务。`TerminalCheckinService` 的调用末尾增加 `CheckinChannel.TERMINAL_ACCOUNT`。

`ReservationRepository` 对应方法改为：

```java
public int checkinReservation(long reservationId, long userId, long labId,
        LocalDateTime checkinTime, com.campus.lab.checkin.CheckinChannel channel) {
    return jdbcTemplate.update("""
            UPDATE t_reservation
            SET status = 'IN_USE', checkin_time = ?, checkin_channel = ?
            WHERE resv_id = ? AND user_id = ? AND lab_id = ? AND status = 'APPROVED'
            """, Timestamp.valueOf(checkinTime), channel.name(), reservationId, userId, labId);
}

public void insertCheckinLog(long reservationId, long operatorId,
        com.campus.lab.checkin.CheckinChannel channel) {
    jdbcTemplate.update("""
            INSERT INTO t_reservation_log (resv_id, from_status, to_status, operator_id, reason)
            VALUES (?, 'APPROVED', 'IN_USE', ?, ?)
            """, reservationId, operatorId, channel.logReason());
}
```

- [ ] **Step 5: 实现二维码编排、DTO 与两个入口**

```java
// QrCheckinRequest.java
package com.campus.lab.checkin.qr.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
public record QrCheckinRequest(@NotNull Long reservationId,
        @NotBlank @Size(max = 2048) String content) { }
```

```java
// QrCheckinView.java
package com.campus.lab.checkin.qr.dto;
import com.campus.lab.reservation.dto.ReservationView;
public record QrCheckinView(ReservationView reservation, boolean alreadyCheckedIn) { }
```

```java
// CheckinCodeView.java
package com.campus.lab.checkin.qr.dto;
public record CheckinCodeView(long labId, String content) { }
```

`QrCheckinService` 按以下完整流程实现，锁释放方式与 `TerminalCheckinService.checkinSelected` 一致：

```java
package com.campus.lab.checkin.qr;

import com.campus.lab.checkin.CheckinChannel;
import com.campus.lab.checkin.ReservationCheckinTransactionService;
import com.campus.lab.checkin.qr.dto.QrCheckinView;
import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.LabLockKey;
import com.campus.lab.reservation.LockManager;
import com.campus.lab.reservation.ReservationLock;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.dto.ReservationView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class QrCheckinService {
    private final CheckinQrService qr;
    private final ReservationRepository repository;
    private final ReservationCheckinTransactionService transaction;
    private final LockManager locks;
    public QrCheckinService(CheckinQrService qr, ReservationRepository repository,
            ReservationCheckinTransactionService transaction, LockManager locks) {
        this.qr = qr; this.repository = repository; this.transaction = transaction; this.locks = locks;
    }
    public QrCheckinView checkin(long reservationId, long userId, String content) {
        long labId = qr.verify(content);
        ReservationRepository.CancellationTarget target = repository.findCancellationTarget(reservationId);
        if (target == null) throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        if (target.labId() == null || target.labId() != labId) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约与二维码实验室不匹配");
        }
        LabLockKey key = new LabLockKey(labId, target.date());
        List<ReservationLock> acquired = locks.acquireAll(List.of(key));
        ReservationCheckinTransactionService.CheckinOutcome outcome;
        try {
            outcome = transaction.checkin(reservationId, userId, labId, key, CheckinChannel.WECHAT_QR);
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) locks.release(acquired.get(i));
        }
        ReservationView view = repository.findView(reservationId);
        return new QrCheckinView(view, outcome.alreadyCheckedIn());
    }
}
```

在 `CheckinController` 注入 `QrCheckinService` 并添加：

```java
@PostMapping("/qr")
@PreAuthorize("hasAnyRole('STUDENT','TEACHER')")
public ApiResponse<com.campus.lab.checkin.qr.dto.QrCheckinView> qrCheckin(
        @jakarta.validation.Valid @RequestBody com.campus.lab.checkin.qr.dto.QrCheckinRequest request,
        Authentication authentication) {
    return ApiResponse.success(qrCheckinService.checkin(request.reservationId(),
            Long.parseLong(authentication.getName()), request.content()));
}
```

将 `ResourceService.requireLab` 保持私有，并新增 `public LabView getLab(long labId) { return requireLab(labId); }`。在 `AdminResourceController` 注入 `CheckinQrService` 与 `ResourceService`，新增：

```java
@GetMapping("/resources/labs/{labId}/checkin-code")
public ApiResponse<com.campus.lab.checkin.qr.dto.CheckinCodeView> checkinCode(@PathVariable long labId) {
    resourceService.getLab(labId);
    return ApiResponse.success(new com.campus.lab.checkin.qr.dto.CheckinCodeView(
            labId, checkinQrService.contentForLab(labId)));
}
```

- [ ] **Step 6: 运行扫码与终端回归测试**

Run: `mvn -Dtest=CheckinQrServiceTest,QrCheckinIntegrationTest,TerminalCheckinIntegrationTest,EarlyCheckinIntegrationTest test`
Expected: PASS，终端渠道仍为 `TERMINAL_ACCOUNT`。

- [ ] **Step 7: 提交本任务（仅在仓库与授权存在时）**

```bash
git add server/src/main/java/com/campus/lab/checkin server/src/main/java/com/campus/lab/reservation/ReservationRepository.java server/src/main/java/com/campus/lab/resource server/src/test/java/com/campus/lab/QrCheckinIntegrationTest.java server/src/test/java/com/campus/lab/checkin/qr
git commit -m "feat: add signed QR check-in channel"
```

### Task 4: 微信签退渠道与 Web 向后兼容

**Files:**
- Create: `server/src/main/java/com/campus/lab/checkin/CheckoutChannel.java`
- Create: `server/src/main/java/com/campus/lab/checkin/dto/CheckoutReservationRequest.java`
- Modify: `server/src/main/java/com/campus/lab/checkin/CheckinController.java`
- Modify: `server/src/main/java/com/campus/lab/checkin/ReservationCheckoutService.java`
- Modify: `server/src/main/java/com/campus/lab/checkin/ReservationCheckoutTransactionService.java`
- Modify: `server/src/main/java/com/campus/lab/reservation/ReservationRepository.java:336-353`
- Test: `server/src/test/java/com/campus/lab/CheckoutIntegrationTest.java`

- [ ] **Step 1: 增加微信渠道和非法渠道失败测试**

在 `CheckoutIntegrationTest` 增加：

```java
@Test
void wechatCheckoutRecordsItsChannelAndMissingBodyKeepsWebDefault() throws Exception {
    at("2026-10-24T02:10:00Z");
    long labId = createLaboratory(2);
    Fixture owner = fixture("STUDENT");
    long reservationId = createReservation(owner.userId(), labId, "SEAT", "IN_USE",
            LocalDateTime.of(2026, 10, 24, 9, 0),
            LocalDateTime.of(2026, 10, 24, 12, 0), true);
    mockMvc.perform(post("/api/checkin/{id}/checkout", reservationId)
                    .header("Authorization", "Bearer " + owner.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"channel\":\"WECHAT_QR\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.checkoutChannel").value("WECHAT_QR"));
}

@Test
void unknownCheckoutChannelIsBadRequest() throws Exception {
    Fixture owner = fixture("STUDENT");
    mockMvc.perform(post("/api/checkin/{id}/checkout", 1)
                    .header("Authorization", "Bearer " + owner.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"channel\":\"CLIENT_INVENTED\"}"))
            .andExpect(status().isBadRequest());
}
```

补充静态导入 `jsonPath`、`status` 和 `MediaType`。Run: `mvn -Dtest=CheckoutIntegrationTest test`
Expected: 新测试编译失败或 400/空渠道断言失败，旧测试仍通过。

- [ ] **Step 2: 实现白名单渠道并逐层传递**

```java
// CheckoutChannel.java
package com.campus.lab.checkin;
public enum CheckoutChannel { WEB_ACCOUNT, WECHAT_QR }
```

```java
// CheckoutReservationRequest.java
package com.campus.lab.checkin.dto;
public record CheckoutReservationRequest(CheckoutChannel channel) { }
```

控制器保持无请求体兼容：

```java
@PostMapping("/{reservationId}/checkout")
@PreAuthorize("hasAnyRole('STUDENT','TEACHER')")
public ApiResponse<ReservationView> checkout(@PathVariable long reservationId,
        @RequestBody(required = false) com.campus.lab.checkin.dto.CheckoutReservationRequest request,
        Authentication authentication) {
    CheckoutChannel channel = request == null || request.channel() == null
            ? CheckoutChannel.WEB_ACCOUNT : request.channel();
    return ApiResponse.success(checkoutService.checkout(
            reservationId, Long.parseLong(authentication.getName()), channel));
}
```

把 `ReservationCheckoutService.checkout` 和 `ReservationCheckoutTransactionService.checkout` 增加 `CheckoutChannel channel` 参数，并传给 repository。把 `checkoutReservation` 的 SQL 改为：

```java
public int checkoutReservation(long reservationId, long userId, LocalDateTime checkoutTime,
        com.campus.lab.checkin.CheckoutChannel channel) {
    return jdbcTemplate.update("""
            UPDATE t_reservation
            SET status = 'COMPLETED', checkout_time = ?, checkout_channel = ?
            WHERE resv_id = ? AND user_id = ? AND status = 'IN_USE'
            """, Timestamp.valueOf(checkoutTime), channel.name(), reservationId, userId);
}
```

- [ ] **Step 3: 运行全部签到签退回归**

Run: `mvn -Dtest=CheckoutIntegrationTest,CheckoutEndIntegrationTest,QrCheckinIntegrationTest,TerminalCheckinIntegrationTest test`
Expected: PASS，旧无 body 请求仍记录 `WEB_ACCOUNT`。

- [ ] **Step 4: 运行完整后端测试**

Run: `mvn test`
Expected: `BUILD SUCCESS`，无既有测试回归。

- [ ] **Step 5: 提交本任务（仅在仓库与授权存在时）**

```bash
git add server/src/main/java/com/campus/lab/checkin server/src/main/java/com/campus/lab/reservation/ReservationRepository.java server/src/test/java/com/campus/lab/CheckoutIntegrationTest.java
git commit -m "feat: record WeChat checkout channel"
```


### Task 5: 原生 TypeScript 工程与可重复执行的基础检查

**Files:**
- Create: `miniprogram/package.json`
- Create: `miniprogram/tsconfig.json`
- Create: `miniprogram/vitest.config.ts`
- Create: `miniprogram/project.config.json`
- Create: `miniprogram/.gitignore`
- Create: `miniprogram/miniprogram/app.ts`
- Create: `miniprogram/miniprogram/app.json`
- Create: `miniprogram/miniprogram/app.wxss`
- Create: `miniprogram/miniprogram/sitemap.json`
- Create: `miniprogram/miniprogram/config/index.ts`
- Create: `miniprogram/miniprogram/pages/home/index.ts、index.wxml、index.wxss、index.json`
- Test: `miniprogram/tests/config.spec.ts`

- [ ] **Step 1: 写开发环境判定失败测试**

```ts
import { describe, expect, it, vi } from 'vitest'

describe('mini program config', () => {
  it('uses the local backend and only exposes development login in develop builds', async () => {
    vi.resetModules()
    vi.stubGlobal('wx', {
      getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
    })
    const config = await import('../miniprogram/config/index')
    expect(config.API_BASE_URL).toBe('http://127.0.0.1:8080')
    expect(config.isDevelopmentLoginVisible()).toBe(true)
    vi.stubGlobal('wx', {
      getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    })
    expect(config.isDevelopmentLoginVisible()).toBe(false)
  })
})
```

Run: `npm install` 尚不可执行时先完成 Step 2 的 manifest，再运行 `npm install && npm test -- --run tests/config.spec.ts`。
Expected: FAIL，找不到 `miniprogram/config/index`。

- [ ] **Step 2: 创建 manifest、TS 配置与测试配置**

`package.json`：

```json
{
  "name": "campus-lab-miniprogram",
  "private": true,
  "version": "1.0.0",
  "scripts": {
    "typecheck": "tsc --noEmit",
    "test": "vitest run"
  },
  "devDependencies": {
    "miniprogram-api-typings": "5.3.1",
    "typescript": "5.7.2",
    "vitest": "3.2.7"
  }
}
```

`tsconfig.json`：

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "CommonJS",
    "moduleResolution": "Node",
    "strict": true,
    "noEmit": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "lib": ["ES2022"],
    "types": ["miniprogram-api-typings"]
  },
  "include": ["miniprogram/**/*.ts", "tests/**/*.ts", "vitest.config.ts"]
}
```

`vitest.config.ts`：

```ts
import { defineConfig } from 'vitest/config'
export default defineConfig({ test: { environment: 'node', include: ['tests/**/*.spec.ts'] } })
```

`project.config.json`：

```json
{
  "description": "LabSpace 微信小程序",
  "compileType": "miniprogram",
  "libVersion": "latest",
  "appid": "touristappid",
  "projectname": "labspace-miniprogram",
  "miniprogramRoot": "miniprogram/",
  "setting": {
    "es6": true,
    "enhance": true,
    "postcss": true,
    "minified": true,
    "urlCheck": false,
    "useCompilerPlugins": ["typescript"]
  }
}
```

`.gitignore`：

```gitignore
node_modules/
dist/
coverage/
project.private.config.json
```

- [ ] **Step 3: 实现配置与最小可运行首页**

`config/index.ts`：

```ts
export const API_BASE_URL = 'http://127.0.0.1:8080'
export function isDevelopmentLoginVisible() {
  return wx.getAccountInfoSync().miniProgram.envVersion === 'develop'
}
```

`app.ts`：

```ts
App({})
```

`app.json`：

```json
{
  "pages": ["pages/home/index"],
  "window": {
    "navigationBarTitleText": "LabSpace",
    "navigationBarBackgroundColor": "#ffffff",
    "navigationBarTextStyle": "black",
    "backgroundColor": "#f4f7fb"
  },
  "sitemapLocation": "sitemap.json"
}
```

`sitemap.json`：

```json
{ "desc": "LabSpace 页面索引配置", "rules": [{ "action": "disallow", "page": "*" }] }
```

`app.wxss`：

```css
page { min-height: 100%; color: #18243a; background: #f4f7fb; font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei UI", sans-serif; font-size: 28rpx; }
view, text, input, textarea, button, picker { box-sizing: border-box; }
button::after { border: 0; }
.page { min-height: 100vh; padding: 30rpx 28rpx 48rpx; }
.card { background: #fff; border: 2rpx solid #e8edf4; border-radius: 24rpx; box-shadow: 0 12rpx 36rpx rgb(32 55 96 / 6%); }
.button-primary { min-height: 88rpx; color: #fff; background: #3867e8; border-radius: 18rpx; font-size: 28rpx; font-weight: 600; line-height: 88rpx; }
.button-secondary { min-height: 72rpx; color: #53627a; background: #fff; border: 2rpx solid #dfe5ee; border-radius: 16rpx; font-size: 26rpx; line-height: 70rpx; }
.muted { color: #77849a; }
.error { margin-bottom: 24rpx; padding: 24rpx; color: #b94451; background: #fff0f1; border: 2rpx solid #f2c7cd; border-radius: 18rpx; font-size: 26rpx; }
.loading, .empty { padding: 64rpx 24rpx; color: #77849a; text-align: center; }
```

首页临时文件使用真实品牌与入口，不写模拟预约数据：

```ts
Page({})
```

```xml
<view class="page"><view class="card home-brand"><text class="home-brand__mark">L</text><text class="home-brand__name">LabSpace</text></view></view>
```

```css
.home-brand { display: flex; align-items: center; gap: 20rpx; padding: 32rpx; }
.home-brand__mark { display: grid; width: 64rpx; height: 64rpx; color: #fff; background: #3867e8; border-radius: 18rpx; place-items: center; }
.home-brand__name { font-size: 36rpx; font-weight: 700; }
```

```json
{ "navigationBarTitleText": "LabSpace" }
```

- [ ] **Step 4: 安装依赖并通过基础检查**

Run: `npm install && npm run typecheck && npm test`
Expected: config 测试 PASS，`tsc` 无错误；生成 `package-lock.json` 并保留。

- [ ] **Step 5: 用微信开发者工具验证 TS 编译**

Open: `miniprogram/project.config.json`。
Expected: 游客 AppID 下首页显示 LabSpace；控制台无 TS 编译错误。正式凭据只在本机项目设置中替换，不写秘密到工程。

- [ ] **Step 6: 提交本任务（仅在仓库与授权存在时）**

```bash
git add miniprogram
git commit -m "chore: scaffold native WeChat mini program"
```

### Task 6: 类型化请求层、身份存储与 401/网络错误语义

**Files:**
- Create: `miniprogram/miniprogram/api/types.ts`
- Create: `miniprogram/miniprogram/api/http.ts`
- Create: `miniprogram/miniprogram/auth/storage.ts`
- Test: `miniprogram/tests/http.spec.ts`
- Test: `miniprogram/tests/auth-storage.spec.ts`

- [ ] **Step 1: 写身份存储失败测试**

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('auth storage', () => {
  beforeEach(() => vi.resetModules())
  it('saves and clears token and user without storing passwords', async () => {
    const values = new Map<string, unknown>()
    vi.stubGlobal('wx', {
      setStorageSync: (key: string, value: unknown) => values.set(key, value),
      getStorageSync: (key: string) => values.get(key) ?? '',
      removeStorageSync: (key: string) => values.delete(key),
    })
    const storage = await import('../miniprogram/auth/storage')
    const user = { userId: 1, account: '20240001', name: '演示学生', roles: ['STUDENT'] as const, status: 'ACTIVE' as const }
    storage.saveSession('jwt', user)
    expect(storage.readSession()).toEqual({ token: 'jwt', user })
    storage.clearSession()
    expect(storage.readSession()).toBeNull()
    expect([...values.values()].join(' ')).not.toContain('Lab@2026')
  })
})
```

Run: `npm test -- --run tests/auth-storage.spec.ts`
Expected: FAIL，模块不存在。

- [ ] **Step 2: 实现后端契约类型与本地身份存储**

`api/types.ts` 定义页面使用的完整字段，不改变后端 JSON：

```ts
export type Role = 'STUDENT' | 'TEACHER' | 'LAB_ADMIN' | 'SYS_ADMIN'
export type ReservationStatus = 'PENDING_SCHEDULE' | 'APPROVED' | 'IN_USE' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'EXPIRED' | 'VIOLATION_TERMINATED'
export interface User { userId: number; account: string; name: string; roles: Role[]; status: 'ACTIVE' | 'DISABLED' }
export interface ApiEnvelope<T> { code: number; message: string; data: T }
export interface LoginResponse { token: string; user: User }
export interface WeChatLoginResponse { bound: boolean; token: string | null; user: User | null; bindTicket: string | null; expiresIn: number | null }
export interface Lab { labId: number; code: string; name: string; location: string; capacity: number; status: 'ACTIVE' | 'DISABLED' | 'MAINTENANCE' }
export interface TimeRule { ruleId: number; labId: number; dayOfWeek: number; openTime: string; closeTime: string; minDurationMinutes: number; maxDurationMinutes: number; advanceMinDays: number; advanceMaxDays: number }
export interface LabRules { labId: number; weeklyRules: TimeRule[]; cancellation: { freeBeforeHours: number }; checkin: { beforeMinutes: number; graceMinutes: number; choiceExpireSeconds: number; maxFailedAttempts: number; lockoutMinutes: number } }
export interface SlotAvailability { index: number; start: string; end: string; roomOccupied: boolean; occupiedSeatCount: number; availableSeatCount: number }
export interface LabAvailability { labId: number; date: string; labStatus: string; roomMask: string; seats: unknown[]; slots: SlotAvailability[] }
export interface ReservationPreferences { zoneCode?: string | null; nearWindow?: boolean | null; minSpecLevel?: string | null; allowedLabIds?: number[] }
export interface ReservationSeat { seatId: number; seatCode: string; zoneCode: string; seatIndex: number; specLevel: string; mask: string; zoneMismatch: boolean; nearWindowMismatch: boolean }
export interface Reservation { resvId: number; batchId: number | null; batchNo: string | null; labId: number | null; labName: string | null; date: string; windowStart: string; windowEnd: string; durationMinutes: number; startTime: string | null; endTime: string | null; allocationMode: 'FIXED' | 'SCHEDULABLE'; reservationMode: 'ROOM' | 'SEAT'; status: ReservationStatus; attendeeCount: number; seatCount: number | null; purpose: string; preferences: ReservationPreferences; seats: ReservationSeat[]; checkinChannel: string | null; checkinTime: string | null; checkoutChannel: string | null; checkoutTime: string | null; createdAt: string }
export interface PageView<T> { items: T[]; page: number; size: number; total: number }
export interface CreateReservationPayload { allocationMode: 'FIXED' | 'SCHEDULABLE'; labId: number | null; date: string; startTime: string | null; endTime: string | null; windowStart: string | null; windowEnd: string | null; durationMinutes: number | null; reservationMode: 'ROOM' | 'SEAT'; attendeeCount: number; seatCount: number | null; purpose: string; preferences: ReservationPreferences }
export interface QrCheckinResponse { reservation: Reservation; alreadyCheckedIn: boolean }
```

`auth/storage.ts`：

```ts
import type { User } from '../api/types'
const TOKEN_KEY = 'campus.lab.token'
const USER_KEY = 'campus.lab.user'
export interface StoredSession { token: string; user: User }
export function saveSession(token: string, user: User) {
  wx.setStorageSync(TOKEN_KEY, token)
  wx.setStorageSync(USER_KEY, user)
}
export function readSession(): StoredSession | null {
  const token = wx.getStorageSync(TOKEN_KEY) as string
  const user = wx.getStorageSync(USER_KEY) as User
  return token && user && user.userId ? { token, user } : null
}
export function clearSession() {
  wx.removeStorageSync(TOKEN_KEY)
  wx.removeStorageSync(USER_KEY)
}
```

Run: `npm test -- --run tests/auth-storage.spec.ts`
Expected: PASS。

- [ ] **Step 3: 写请求解包、401 与网络失败测试**

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('http', () => {
  beforeEach(() => { vi.resetModules(); vi.clearAllMocks() })
  it('unwraps successful responses and sends bearer token', async () => {
    const remove = vi.fn()
    vi.stubGlobal('wx', {
      getStorageSync: (key: string) => key === 'campus.lab.token' ? 'jwt' : { userId: 1 },
      removeStorageSync: remove,
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 200, data: { code: 0, message: 'success', data: { ok: true } } } as never),
    })
    vi.stubGlobal('getCurrentPages', () => [{ route: 'pages/home/index' }])
    const http = await import('../miniprogram/api/http')
    await expect(http.request<{ ok: boolean }>('/api/example')).resolves.toEqual({ ok: true })
  })

  it('clears identity on 401 but preserves it on network failure', async () => {
    const remove = vi.fn()
    const reLaunch = vi.fn()
    vi.stubGlobal('wx', {
      getStorageSync: () => '',
      removeStorageSync: remove,
      reLaunch,
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 401, data: { code: 401, message: ' expired ', data: null } } as never),
    })
    vi.stubGlobal('getCurrentPages', () => [{ route: 'pages/home/index' }])
    const http = await import('../miniprogram/api/http')
    await expect(http.request('/api/private')).rejects.toMatchObject({ status: 401 })
    expect(remove).toHaveBeenCalledTimes(2)
    expect(reLaunch).toHaveBeenCalledWith({ url: '/pages/login/index' })

    vi.stubGlobal('wx', {
      getStorageSync: () => '', removeStorageSync: remove,
      request: (options: WechatMiniprogram.RequestOption) => options.fail?.({ errMsg: 'offline' }),
    })
    await expect(http.request('/api/private')).rejects.toMatchObject({ status: 0 })
    expect(remove).toHaveBeenCalledTimes(2)
  })
})
```

Run: `npm test -- --run tests/http.spec.ts`
Expected: FAIL，模块不存在。

- [ ] **Step 4: 实现请求层**

```ts
import { API_BASE_URL } from '../config/index'
import { clearSession, readSession } from '../auth/storage'
import type { ApiEnvelope } from './types'

export class ApiError extends Error {
  constructor(message: string, public status = 0, public code?: number) { super(message); this.name = 'ApiError' }
}
interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  data?: unknown
  query?: Record<string, string | number | undefined>
  redirectOnUnauthorized?: boolean
}
export function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const query = Object.entries(options.query ?? {})
    .filter((entry): entry is [string, string | number] => entry[1] !== undefined)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`).join('&')
  const token = readSession()?.token
  return new Promise<T>((resolve, reject) => {
    wx.request({
      url: `${API_BASE_URL}${path}${query ? `?${query}` : ''}`,
      method: options.method ?? 'GET', data: options.data as never, timeout: 20000,
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success: (response) => {
        const body = response.data as ApiEnvelope<T>
        if (response.statusCode === 401) {
          clearSession()
          const current = getCurrentPages().at(-1)?.route
          if (options.redirectOnUnauthorized !== false && current !== 'pages/login/index') {
            wx.reLaunch({ url: '/pages/login/index' })
          }
          reject(new ApiError(body.message || '登录已过期', 401, body.code)); return
        }
        if (response.statusCode >= 200 && response.statusCode < 300 && body.code === 0) {
          resolve(body.data); return
        }
        reject(new ApiError(body.message || (response.statusCode === 403 ? '当前账号无权执行此操作' : '请求失败'), response.statusCode, body.code))
      },
      fail: () => reject(new ApiError('网络连接失败，请检查服务是否启动', 0)),
    })
  })
}
export function errorMessage(error: unknown) {
  return error instanceof Error && error.message ? error.message : '请求失败，请稍后重试'
}
```

Run: `npm run typecheck && npm test -- --run tests/http.spec.ts tests/auth-storage.spec.ts`
Expected: PASS。

- [ ] **Step 5: 提交本任务（仅在仓库与授权存在时）**

```bash
git add miniprogram/miniprogram/api miniprogram/miniprogram/auth miniprogram/tests/http.spec.ts miniprogram/tests/auth-storage.spec.ts
git commit -m "feat: add mini program API and session infrastructure"
```

### Task 7: 微信/开发登录、绑定页与身份恢复

**Files:**
- Create: `miniprogram/miniprogram/api/auth.ts`
- Create: `miniprogram/miniprogram/auth/session.ts`
- Create: `miniprogram/miniprogram/auth/login-flow.ts`
- Create: `miniprogram/miniprogram/pages/login/index.ts、index.wxml、index.wxss、index.json`
- Create: `miniprogram/miniprogram/pages/bind/index.ts、index.wxml、index.wxss、index.json`
- Create: `miniprogram/miniprogram/pages/labs/index.ts、index.wxml、index.wxss、index.json`
- Create: `miniprogram/miniprogram/pages/profile/index.ts、index.wxml、index.wxss、index.json`
- Modify: `miniprogram/miniprogram/app.ts`
- Modify: `miniprogram/miniprogram/app.json`
- Test: `miniprogram/tests/login-flow.spec.ts`

- [ ] **Step 1: 写微信登录分支失败测试**

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('WeChat login flow', () => {
  beforeEach(() => { vi.resetModules(); vi.clearAllMocks() })
  it('saves bound login and returns ticket for binding without saving a token', async () => {
    const set = vi.fn()
    const user = { userId: 1, account: '20240001', name: '演示学生', roles: ['STUDENT'], status: 'ACTIVE' }
    vi.stubGlobal('wx', {
      login: (options: WechatMiniprogram.LoginOption) => options.success?.({ code: 'code-1' }),
      setStorageSync: set,
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 200, data: { code: 0, message: 'success', data: { bound: false, token: null, user: null, bindTicket: 'ticket-1', expiresIn: 300 } } } as never),
      getStorageSync: () => '', removeStorageSync: vi.fn(),
    })
    vi.stubGlobal('getCurrentPages', () => [])
    const flow = await import('../miniprogram/auth/login-flow')
    await expect(flow.loginWithWeChat()).resolves.toEqual({ bound: false, bindTicket: 'ticket-1' })
    expect(set).not.toHaveBeenCalled()
  })
})
```

Run: `npm test -- --run tests/login-flow.spec.ts`
Expected: FAIL，模块不存在。

- [ ] **Step 2: 实现认证 API、登录流程和恢复逻辑**

`api/auth.ts`：

```ts
import { request } from './http'
import type { LoginResponse, User, WeChatLoginResponse } from './types'
export function developmentLogin(account: string, password: string) {
  return request<LoginResponse>('/api/auth/login', { method: 'POST', data: { account, password }, redirectOnUnauthorized: false })
}
export function wechatLogin(code: string) {
  return request<WeChatLoginResponse>('/api/auth/wechat/login', { method: 'POST', data: { code }, redirectOnUnauthorized: false })
}
export function wechatBind(bindTicket: string, account: string, password: string) {
  return request<LoginResponse>('/api/auth/wechat/bind', { method: 'POST', data: { bindTicket, account, password }, redirectOnUnauthorized: false })
}
export function currentUser() { return request<User>('/api/auth/me') }
```

`auth/login-flow.ts`：

```ts
import { wechatLogin } from '../api/auth'
import { saveSession } from './storage'
import type { User } from '../api/types'
export type WeChatFlowResult = { bound: true; user: User } | { bound: false; bindTicket: string }
export function loginWithWeChat(): Promise<WeChatFlowResult> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: async ({ code }) => {
        try {
          const result = await wechatLogin(code)
          if (result.bound && result.token && result.user) {
            saveSession(result.token, result.user)
            resolve({ bound: true, user: result.user })
          } else if (!result.bound && result.bindTicket) {
            resolve({ bound: false, bindTicket: result.bindTicket })
          } else reject(new Error('微信登录响应无效'))
        } catch (error) { reject(error) }
      },
      fail: () => reject(new Error('无法获取微信登录凭证')),
    })
  })
}
```

`auth/session.ts`：

```ts
import { currentUser } from '../api/auth'
import { readSession, saveSession } from './storage'
export async function restoreSession() {
  const stored = readSession()
  if (!stored) { wx.reLaunch({ url: '/pages/login/index' }); return null }
  try {
    const user = await currentUser()
    saveSession(stored.token, user)
    return user
  } catch (error) {
    if ((error as { status?: number }).status !== 401) wx.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
    return null
  }
}
```

- [ ] **Step 3: 实现登录与绑定页面**

登录 `index.ts`：

```ts
import { developmentLogin } from '../../api/auth'
import { errorMessage } from '../../api/http'
import { isDevelopmentLoginVisible } from '../../config/index'
import { loginWithWeChat } from '../../auth/login-flow'
import { saveSession } from '../../auth/storage'
Page({
  data: { development: false, account: '', password: '', loading: false, error: '' },
  onLoad() { this.setData({ development: isDevelopmentLoginVisible() }) },
  onAccount(event: WechatMiniprogram.Input) { this.setData({ account: event.detail.value }) },
  onPassword(event: WechatMiniprogram.Input) { this.setData({ password: event.detail.value }) },
  async wechat() {
    this.setData({ loading: true, error: '' })
    try {
      const result = await loginWithWeChat()
      if (result.bound) wx.switchTab({ url: '/pages/home/index' })
      else wx.navigateTo({ url: `/pages/bind/index?ticket=${encodeURIComponent(result.bindTicket)}` })
    } catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ loading: false }) }
  },
  async developmentLogin() {
    if (!this.data.development || this.data.loading) return
    this.setData({ loading: true, error: '' })
    try {
      const result = await developmentLogin(this.data.account.trim(), this.data.password)
      saveSession(result.token, result.user)
      wx.switchTab({ url: '/pages/home/index' })
    } catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ loading: false, password: '' }) }
  },
})
```

登录 `index.wxml`：

```xml
<view class="page auth-page">
  <view class="auth-brand"><text class="auth-brand__mark">L</text><text class="auth-brand__name">LabSpace</text></view>
  <view class="auth-title">高校实验室预约</view><view class="muted auth-subtitle">查询空闲时段，完成预约与到场签到</view>
  <view wx:if="{{error}}" class="error">{{error}}</view>
  <button class="button-primary" loading="{{loading}}" bindtap="wechat">微信一键登录</button>
  <view wx:if="{{development}}" class="development card">
    <view class="development__title">开发登录</view><view class="muted development__note">仅开发版显示，使用现有校园账号</view>
    <input class="auth-input" placeholder="学号" value="{{account}}" bindinput="onAccount" />
    <input class="auth-input" password placeholder="密码" value="{{password}}" bindinput="onPassword" />
    <button class="button-secondary" disabled="{{loading}}" bindtap="developmentLogin">账号密码登录</button>
  </view>
</view>
```

登录 `index.wxss`：

```css
.auth-page { padding-top: 15vh; }
.auth-brand { display: flex; align-items: center; justify-content: center; gap: 18rpx; }
.auth-brand__mark { display: grid; width: 72rpx; height: 72rpx; color: #fff; background: #3867e8; border-radius: 20rpx; place-items: center; font-weight: 800; }
.auth-brand__name { font-size: 42rpx; font-weight: 750; }
.auth-title { margin-top: 58rpx; font-size: 44rpx; font-weight: 700; text-align: center; }
.auth-subtitle { margin: 16rpx 0 48rpx; text-align: center; }
.development { margin-top: 36rpx; padding: 28rpx; }
.development__title { font-weight: 650; }.development__note { margin: 8rpx 0 20rpx; font-size: 24rpx; }
.auth-input { height: 84rpx; margin-bottom: 18rpx; padding: 0 24rpx; background: #f8faff; border: 2rpx solid #e8edf4; border-radius: 16rpx; }
```

登录 `index.json`：`{ "navigationBarTitleText": "登录" }`。

绑定页复用同一 WXSS 引入 `@import "../login/index.wxss";`，逻辑如下：

```ts
import { wechatBind } from '../../api/auth'
import { errorMessage } from '../../api/http'
import { saveSession } from '../../auth/storage'
Page({
  data: { ticket: '', account: '', password: '', loading: false, error: '' },
  onLoad(query: Record<string, string>) { this.setData({ ticket: query.ticket ?? '' }) },
  onAccount(event: WechatMiniprogram.Input) { this.setData({ account: event.detail.value }) },
  onPassword(event: WechatMiniprogram.Input) { this.setData({ password: event.detail.value }) },
  async bind() {
    if (this.data.loading) return
    this.setData({ loading: true, error: '' })
    try {
      const result = await wechatBind(this.data.ticket, this.data.account.trim(), this.data.password)
      saveSession(result.token, result.user)
      wx.switchTab({ url: '/pages/home/index' })
    } catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ loading: false, password: '' }) }
  },
})
```

绑定 WXML：

```xml
<view class="page auth-page"><view class="auth-title">绑定校园账号</view><view class="muted auth-subtitle">首次使用需验证学号与密码，绑定凭证 5 分钟内有效</view><view wx:if="{{error}}" class="error">{{error}}</view><input class="auth-input" placeholder="学号" value="{{account}}" bindinput="onAccount" /><input class="auth-input" password placeholder="密码" value="{{password}}" bindinput="onPassword" /><button class="button-primary" loading="{{loading}}" bindtap="bind">验证并绑定</button></view>
```

绑定 JSON：`{ "navigationBarTitleText": "绑定校园账号" }`。

- [ ] **Step 4: 注册页面、底部导航和启动恢复**

`app.ts`：

```ts
import { restoreSession } from './auth/session'
App({ onLaunch() { void restoreSession() } })
```

`app.json` 使用以下完整配置：

```json
{
  "pages": ["pages/home/index", "pages/labs/index", "pages/profile/index", "pages/login/index", "pages/bind/index"],
  "window": { "navigationBarTitleText": "LabSpace", "navigationBarBackgroundColor": "#ffffff", "navigationBarTextStyle": "black", "backgroundColor": "#f4f7fb" },
  "tabBar": { "color": "#77849a", "selectedColor": "#3867e8", "backgroundColor": "#ffffff", "list": [
    { "pagePath": "pages/home/index", "text": "首页" },
    { "pagePath": "pages/labs/index", "text": "实验室" },
    { "pagePath": "pages/profile/index", "text": "我的" }
  ] },
  "sitemapLocation": "sitemap.json"
}
```

实验室页本任务的四个完整文件分别为：`Page({})`；`<view class="page"><view class="page-title">实验室</view></view>`；`.page-title { font-size: 40rpx; font-weight: 700; }`；`{ "navigationBarTitleText": "实验室" }`。个人中心四个文件分别为：`Page({})`；`<view class="page"><view class="page-title">我的</view></view>`；`.page-title { font-size: 40rpx; font-weight: 700; }`；`{ "navigationBarTitleText": "我的" }`。这些中间页面不返回或展示模拟业务数据。

- [ ] **Step 5: 运行登录测试与编译检查**

Run: `npm run typecheck && npm test`
Expected: PASS。开发者工具中未登录进入登录页，开发版显示账号登录，真机/体验版配置不会显示开发入口。

- [ ] **Step 6: 提交本任务（仅在仓库与授权存在时）**

```bash
git add miniprogram
git commit -m "feat: add mini program authentication flow"
```

### Task 8: 连续时间片纯逻辑与可复用选择组件

**Files:**
- Create: `miniprogram/miniprogram/utils/slots.ts`
- Create: `miniprogram/miniprogram/components/slot-grid/index.ts、index.wxml、index.wxss、index.json`
- Test: `miniprogram/tests/slots.spec.ts`

- [ ] **Step 1: 写起点、反向、冲突和开放时间失败测试**

```ts
import { describe, expect, it } from 'vitest'
import { chooseSlot, type SlotRange } from '../miniprogram/utils/slots'
import type { SlotAvailability } from '../miniprogram/api/types'
const slot = (index: number, availableSeatCount = 5, roomOccupied = false): SlotAvailability => ({ index, start: `${String(Math.floor(index / 2)).padStart(2, '0')}:${index % 2 ? '30' : '00'}`, end: '', roomOccupied, occupiedSeatCount: 0, availableSeatCount })
describe('continuous slot selection', () => {
  it('selects a start and swaps a reverse end', () => {
    const slots = Array.from({ length: 48 }, (_, i) => slot(i))
    expect(chooseSlot(slots, null, 16, 'SEAT', '08:00', '22:00')).toEqual({ range: { start: 16, end: null }, error: '' })
    expect(chooseSlot(slots, { start: 16, end: null }, 14, 'SEAT', '08:00', '22:00')).toEqual({ range: { start: 14, end: 16 }, error: '' })
  })
  it('rejects occupied or out-of-hours ranges', () => {
    const slots = Array.from({ length: 48 }, (_, i) => slot(i))
    slots[15]!.roomOccupied = true
    expect(chooseSlot(slots, { start: 14, end: null }, 16, 'ROOM', '08:00', '22:00').error).toBe('所选区间包含不可预约时段')
    expect(chooseSlot(slots, null, 2, 'SEAT', '08:00', '22:00').error).toBe('所选时段不在开放时间内')
  })
  it('starts a new selection when an existing range is clicked', () => {
    const slots = Array.from({ length: 48 }, (_, i) => slot(i))
    const current: SlotRange = { start: 14, end: 16 }
    expect(chooseSlot(slots, current, 20, 'SEAT', '08:00', '22:00').range).toEqual({ start: 20, end: null })
  })
})
```

Run: `npm test -- --run tests/slots.spec.ts`
Expected: FAIL，模块不存在。

- [ ] **Step 2: 实现纯时间片选择**

```ts
import type { SlotAvailability } from '../api/types'
export interface SlotRange { start: number; end: number | null }
export interface SlotChoice { range: SlotRange | null; error: string }
function minute(value: string) {
  const [hour, minutePart] = value.split(':').map(Number)
  return hour === 24 ? 1440 : (hour ?? 0) * 60 + (minutePart ?? 0)
}
export function slotAvailable(slot: SlotAvailability, mode: 'ROOM' | 'SEAT', openTime: string, closeTime: string) {
  const start = slot.index * 30
  return start >= minute(openTime) && start + 30 <= minute(closeTime)
    && (mode === 'ROOM' ? !slot.roomOccupied : slot.availableSeatCount > 0)
}
export function chooseSlot(slots: SlotAvailability[], current: SlotRange | null, index: number,
    mode: 'ROOM' | 'SEAT', openTime: string, closeTime: string): SlotChoice {
  const target = slots[index]
  if (!target) return { range: current, error: '当前时段不可预约' }
  const startMinute = target.index * 30
  if (startMinute < minute(openTime) || startMinute + 30 > minute(closeTime)) {
    return { range: current, error: '所选时段不在开放时间内' }
  }
  if (!slotAvailable(target, mode, openTime, closeTime)) return { range: current, error: '当前时段不可预约' }
  if (!current || current.end !== null) return { range: { start: index, end: null }, error: '' }
  const start = Math.min(current.start, index)
  const end = Math.max(current.start, index)
  for (let cursor = start; cursor <= end; cursor++) {
    const item = slots[cursor]
    if (!item || !slotAvailable(item, mode, openTime, closeTime)) return { range: current, error: '所选区间包含不可预约时段' }
  }
  return { range: { start, end }, error: '' }
}
```

Run: `npm test -- --run tests/slots.spec.ts`
Expected: PASS，开放时间、资源占用和区间冲突分别返回明确文案。

- [ ] **Step 3: 实现 slot-grid 组件**

`index.ts`：

```ts
import { chooseSlot, type SlotRange } from '../../utils/slots'
import type { SlotAvailability } from '../../api/types'
Component({
  properties: {
    slots: { type: Array, value: [] as SlotAvailability[] },
    mode: { type: String, value: 'SEAT' },
    openTime: { type: String, value: '00:00' },
    closeTime: { type: String, value: '24:00' },
    selection: { type: Object, value: null as SlotRange | null },
  },
  methods: {
    select(event: WechatMiniprogram.TouchEvent) {
      const index = Number(event.currentTarget.dataset.index)
      const result = chooseSlot(this.data.slots as SlotAvailability[], this.data.selection as SlotRange | null,
        index, this.data.mode as 'ROOM' | 'SEAT', this.data.openTime, this.data.closeTime)
      if (result.error) { wx.showToast({ title: result.error, icon: 'none' }); return }
      this.triggerEvent('change', result.range)
    },
  },
})
```

`index.wxml`：

```xml
<view class="slot-grid"><view wx:for="{{slots}}" wx:key="index" class="slot {{selection && index >= selection.start && (selection.end === null ? index === selection.start : index <= selection.end) ? 'slot--selected' : ''}} {{roomOccupied && mode === 'ROOM' || availableSeatCount === 0 && mode === 'SEAT' ? 'slot--disabled' : ''}}" data-index="{{index}}" bindtap="select"><text>{{start}}</text><text class="slot__meta">{{selection && selection.start === index ? '起点' : selection && selection.end === index ? '终点' : mode === 'ROOM' ? roomOccupied ? '已占用' : '可预约' : availableSeatCount + ' 位'}}</text></view></view>
```

`index.wxss`：

```css
.slot-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12rpx; }
.slot { display: flex; min-height: 88rpx; padding: 12rpx 4rpx; color: #53627a; background: #f7f9fc; border: 2rpx solid #edf0f5; border-radius: 14rpx; align-items: center; justify-content: center; flex-direction: column; font-size: 22rpx; }
.slot__meta { margin-top: 6rpx; color: #96a1b2; font-size: 18rpx; }.slot--selected { color: #3867e8; background: #eaf0ff; border-color: #a9bff7; }.slot--disabled { color: #a0aaba; background: #f1f3f6; }
```

`index.json`：`{ "component": true }`。

- [ ] **Step 4: 运行检查并提交**

Run: `npm run typecheck && npm test -- --run tests/slots.spec.ts`
Expected: PASS。

```bash
git add miniprogram/miniprogram/utils/slots.ts miniprogram/miniprogram/components/slot-grid miniprogram/tests/slots.spec.ts
git commit -m "feat: add continuous time slot selector"
```


### Task 9: 实验室、开放规则与实时可用性页面

**Files:**
- Create: `miniprogram/miniprogram/api/resources.ts`
- Create: `miniprogram/miniprogram/utils/format.ts`
- Modify: `miniprogram/miniprogram/pages/labs/index.ts、index.wxml、index.wxss、index.json`
- Test: `miniprogram/tests/format.spec.ts`

- [ ] **Step 1: 写本地日期和 24:00 格式化失败测试**

```ts
import { describe, expect, it } from 'vitest'
import { addDays, reservationTime, timeOnly, toDateInput } from '../miniprogram/utils/format'
describe('local formatting', () => {
  it('formats dates without UTC conversion and supports midnight end', () => {
    expect(toDateInput(new Date(2026, 10, 7))).toBe('2026-11-07')
    expect(addDays('2026-11-07', 1)).toBe('2026-11-08')
    expect(timeOnly('2026-11-08T00:00:00', true)).toBe('24:00')
  })
  it('uses formal time or schedulable window', () => {
    const base = { startTime: '2026-11-08T14:00:00', endTime: '2026-11-08T16:00:00', date: '2026-11-08', windowStart: '08:00:00', windowEnd: '12:00:00', durationMinutes: 60 }
    expect(reservationTime(base)).toBe('2026-11-08 14:00–16:00')
    expect(reservationTime({ ...base, startTime: null, endTime: null })).toBe('2026-11-08 08:00–12:00（60分钟）')
  })
})
```

Run: `npm test -- --run tests/format.spec.ts`
Expected: FAIL，模块不存在。

- [ ] **Step 2: 实现格式化与资源 API**

`utils/format.ts`：

```ts
export const pad = (value: number) => String(value).padStart(2, '0')
export function toDateInput(date: Date) { return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` }
export function today() { return toDateInput(new Date()) }
export function addDays(date: string, amount: number) { const value = new Date(`${date}T00:00:00`); value.setDate(value.getDate() + amount); return toDateInput(value) }
export function timeOnly(value: string | null | undefined, endOfDay = false) { if (!value) return '—'; const time = value.includes('T') ? value.slice(value.indexOf('T') + 1) : value; if (endOfDay && (time.startsWith('24:00') || time.startsWith('00:00:00'))) return '24:00'; return time.slice(0, 5) }
export function formatDateTime(value: string | null | undefined) { return value ? value.replace('T', ' ').slice(0, 19) : '—' }
export function reservationTime(item: { startTime: string | null; endTime: string | null; date: string; windowStart: string; windowEnd: string; durationMinutes: number }) { return item.startTime && item.endTime ? `${formatDateTime(item.startTime).slice(0, 16)}–${timeOnly(item.endTime, true)}` : `${item.date} ${timeOnly(item.windowStart)}–${timeOnly(item.windowEnd, true)}（${item.durationMinutes}分钟）` }
export const statusLabels: Record<string, string> = { ACTIVE: '正常', DISABLED: '停用', MAINTENANCE: '维护中', PENDING_SCHEDULE: '待排期', APPROVED: '已通过', IN_USE: '使用中', COMPLETED: '已完成', CANCELLED: '已取消', NO_SHOW: '未到场', EXPIRED: '已过期', VIOLATION_TERMINATED: '违规终止', ROOM: '整室', SEAT: '工位', FIXED: '固定时段', SCHEDULABLE: '可调度' }
```

`api/resources.ts`：

```ts
import { request } from './http'
import type { Lab, LabAvailability, LabRules } from './types'
export function listLabs() { return request<Lab[]>('/api/labs') }
export function labRules(labId: number) { return request<LabRules>(`/api/labs/${labId}/rules`) }
export function labAvailability(labId: number, date: string) { return request<LabAvailability>(`/api/labs/${labId}/availability`, { query: { date } }) }
```

Run: `npm test -- --run tests/format.spec.ts`
Expected: PASS。

- [ ] **Step 3: 实现实验室页面逻辑**

```ts
import { labAvailability, labRules, listLabs } from '../../api/resources'
import { errorMessage } from '../../api/http'
import type { Lab, LabAvailability, LabRules, TimeRule } from '../../api/types'
import { addDays, today, timeOnly } from '../../utils/format'
import { restoreSession } from '../../auth/session'
import type { SlotRange } from '../../utils/slots'
Page({
  data: {
    labs: [] as Lab[], labNames: [] as string[], labIndex: 0, labId: 0,
    date: addDays(today(), 1), mode: 'SEAT' as 'ROOM' | 'SEAT',
    rules: null as LabRules | null, availability: null as LabAvailability | null,
    dayRule: null as TimeRule | null, openTime: '00:00', closeTime: '24:00',
    selection: null as SlotRange | null, selectedText: '',
    loading: false, error: '',
  },
  async onShow() { if (await restoreSession()) await this.loadLabs() },
  async loadLabs() {
    this.setData({ loading: true, error: '' })
    try {
      const labs = await listLabs()
      this.setData({ labs, labNames: labs.map(lab => `${lab.code} · ${lab.name}`), labId: labs[0]?.labId ?? 0 })
      if (labs[0]) await this.loadAvailability()
    } catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ loading: false }) }
  },
  async loadAvailability() {
    if (!this.data.labId) return
    this.setData({ loading: true, error: '', selection: null, selectedText: '' })
    try {
      const [rules, availability] = await Promise.all([labRules(this.data.labId), labAvailability(this.data.labId, this.data.date)])
      const jsDay = new Date(`${this.data.date}T00:00:00`).getDay()
      const dayRule = rules.weeklyRules.find(rule => rule.dayOfWeek === (jsDay || 7)) ?? null
      this.setData({ rules, availability, dayRule, openTime: timeOnly(dayRule?.openTime ?? '00:00'), closeTime: timeOnly(dayRule?.closeTime ?? '24:00', true) })
    } catch (error) { this.setData({ error: errorMessage(error), rules: null, availability: null }) }
    finally { this.setData({ loading: false }) }
  },
  onLab(event: WechatMiniprogram.PickerChange) { const labIndex = Number(event.detail.value); this.setData({ labIndex, labId: this.data.labs[labIndex]?.labId ?? 0 }); void this.loadAvailability() },
  onDate(event: WechatMiniprogram.PickerChange) { this.setData({ date: event.detail.value }); void this.loadAvailability() },
  onMode(event: WechatMiniprogram.TouchEvent) { this.setData({ mode: event.currentTarget.dataset.mode, selection: null, selectedText: '' }) },
  onSelection(event: WechatMiniprogram.CustomEvent<SlotRange>) {
    const range = event.detail
    const slots = this.data.availability?.slots ?? []
    this.setData({ selection: range, selectedText: range.end === null ? '' : `${slots[range.start]?.start}–${slots[range.end]?.end}` })
  },
  goReserve() {
    const range = this.data.selection
    const slots = this.data.availability?.slots ?? []
    if (!range || range.end === null) { wx.showToast({ title: '请选择完整连续时段', icon: 'none' }); return }
    const query = `labId=${this.data.labId}&date=${this.data.date}&startTime=${slots[range.start]?.start}&endTime=${slots[range.end]?.end}&mode=${this.data.mode}`
    wx.navigateTo({ url: `/pages/reservation-form/index?${query}` })
  },
})
```

- [ ] **Step 4: 实现实验室页面结构与样式**

`index.wxml`：

```xml
<view class="page"><view class="page-title">实验室</view><view class="muted page-subtitle">查看开放规则与每 30 分钟实时余量</view><view wx:if="{{error}}" class="error">{{error}}<text bindtap="loadAvailability" class="retry">重试</text></view><picker range="{{labNames}}" value="{{labIndex}}" bindchange="onLab"><view class="card picker-card">{{labNames[labIndex] || '暂无实验室'}}<text>›</text></view></picker><view class="card filters"><picker mode="date" value="{{date}}" bindchange="onDate"><view class="filter-value">{{date}}</view></picker><view class="segment"><text class="{{mode === 'ROOM' ? 'active' : ''}}" data-mode="ROOM" bindtap="onMode">整室</text><text class="{{mode === 'SEAT' ? 'active' : ''}}" data-mode="SEAT" bindtap="onMode">工位</text></view></view><view wx:if="{{loading}}" class="loading">正在读取开放规则与占用情况</view><view wx:elif="{{availability}}" class="card availability"><view class="availability__head"><text>选择连续时段</text><text class="muted">每格 30 分钟</text></view><slot-grid slots="{{availability.slots}}" mode="{{mode}}" openTime="{{openTime}}" closeTime="{{closeTime}}" selection="{{selection}}" bind:change="onSelection" /><view wx:if="{{dayRule}}" class="muted rule-note">开放 {{openTime}}–{{closeTime}} · 预约 {{dayRule.minDurationMinutes}}–{{dayRule.maxDurationMinutes}} 分钟 · 提前 {{dayRule.advanceMinDays}}–{{dayRule.advanceMaxDays}} 天</view><view wx:else class="empty">当日未开放</view></view><view wx:if="{{selectedText}}" class="selection-bar"><view><text class="muted">已选时段</text><view>{{selectedText}}</view></view><button class="button-primary" bindtap="goReserve">去预约</button></view></view>
```

`index.wxss`：

```css
.page-title { font-size: 40rpx; font-weight: 700; }.page-subtitle { margin: 10rpx 0 28rpx; }.picker-card { display: flex; justify-content: space-between; padding: 28rpx; }.filters { display: flex; align-items: center; justify-content: space-between; margin: 20rpx 0; padding: 20rpx; }.filter-value { color: #3867e8; }.segment { display: flex; padding: 6rpx; background: #e9edf4; border-radius: 14rpx; }.segment text { padding: 12rpx 24rpx; color: #77849a; }.segment .active { color: #3867e8; background: #fff; border-radius: 10rpx; }.availability { padding: 24rpx; }.availability__head { display: flex; justify-content: space-between; margin-bottom: 20rpx; font-size: 26rpx; }.rule-note { margin-top: 24rpx; font-size: 23rpx; line-height: 1.7; }.selection-bar { position: sticky; bottom: 20rpx; display: flex; align-items: center; justify-content: space-between; margin-top: 20rpx; padding: 20rpx 24rpx; background: #fff; border-radius: 20rpx; box-shadow: 0 12rpx 32rpx rgb(32 55 96 / 12%); }.selection-bar .button-primary { min-height: 68rpx; margin: 0; padding: 0 28rpx; line-height: 68rpx; }.retry { margin-left: 16rpx; text-decoration: underline; }
```

`index.json`：`{ "navigationBarTitleText": "实验室", "usingComponents": { "slot-grid": "/components/slot-grid/index" } }`。

- [ ] **Step 5: 运行检查并提交**

Run: `npm run typecheck && npm test`
Expected: PASS；开发者工具选择实验室/日期会调用真实接口，点选两个时间片产生连续区间。

```bash
git add miniprogram/miniprogram/api/resources.ts miniprogram/miniprogram/utils/format.ts miniprogram/miniprogram/pages/labs miniprogram/tests/format.spec.ts
git commit -m "feat: add mini program laboratory availability page"
```

### Task 10: 固定/可调度预约提交与表单保留

**Files:**
- Create: `miniprogram/miniprogram/api/reservations.ts`
- Create: `miniprogram/miniprogram/utils/reservation-form.ts`
- Create: `miniprogram/miniprogram/pages/reservation-form/index.ts、index.wxml、index.wxss、index.json`
- Modify: `miniprogram/miniprogram/app.json`
- Test: `miniprogram/tests/reservation-form.spec.ts`

- [ ] **Step 1: 写两种 payload 与无效输入失败测试**

```ts
import { describe, expect, it } from 'vitest'
import { buildReservationPayload, type ReservationFormState } from '../miniprogram/utils/reservation-form'
const state: ReservationFormState = { allocationMode: 'FIXED', labId: 1, date: '2026-11-08', startTime: '14:00', endTime: '16:00', windowStart: '08:00', windowEnd: '12:00', durationMinutes: 60, reservationMode: 'SEAT', attendeeCount: 2, purpose: ' 项目实验 ', zoneCode: 'NORTH', nearWindow: '', minSpecLevel: '' }
describe('reservation payload', () => {
  it('builds fixed and schedulable requests with matching seat count', () => {
    expect(buildReservationPayload(state)).toMatchObject({ allocationMode: 'FIXED', startTime: '14:00', endTime: '16:00', seatCount: 2, purpose: '项目实验' })
    expect(buildReservationPayload({ ...state, allocationMode: 'SCHEDULABLE' })).toMatchObject({ startTime: null, endTime: null, windowStart: '08:00', windowEnd: '12:00', durationMinutes: 60 })
  })
  it('rejects missing purpose, excessive duration and invalid window', () => {
    expect(() => buildReservationPayload({ ...state, purpose: ' ' })).toThrow('请完整填写实验室、日期、人数和用途')
    expect(() => buildReservationPayload({ ...state, startTime: '08:00', endTime: '13:00' })).toThrow('固定预约一次最多选择8个时间片')
    expect(() => buildReservationPayload({ ...state, allocationMode: 'SCHEDULABLE', durationMinutes: 45 })).toThrow('可调度时长必须为30到240分钟之间的30分钟倍数')
  })
})
```

Run: `npm test -- --run tests/reservation-form.spec.ts`
Expected: FAIL，模块不存在。

- [ ] **Step 2: 实现纯 payload 构建器和完整预约 API**

```ts
import type { CreateReservationPayload } from '../api/types'
export interface ReservationFormState { allocationMode: 'FIXED' | 'SCHEDULABLE'; labId: number; date: string; startTime: string; endTime: string; windowStart: string; windowEnd: string; durationMinutes: number; reservationMode: 'ROOM' | 'SEAT'; attendeeCount: number; purpose: string; zoneCode: string; nearWindow: '' | 'true' | 'false'; minSpecLevel: string }
function minute(value: string) { const [hour, minutePart] = value.split(':').map(Number); return (hour ?? 0) * 60 + (minutePart ?? 0) }
export function buildReservationPayload(form: ReservationFormState): CreateReservationPayload {
  if (!form.labId || !form.date || !form.purpose.trim() || form.attendeeCount < 1) throw new Error('请完整填写实验室、日期、人数和用途')
  const fixed = form.allocationMode === 'FIXED'
  if (fixed && (minute(form.endTime) <= minute(form.startTime) || minute(form.endTime) - minute(form.startTime) > 240)) throw new Error('固定预约一次最多选择8个时间片')
  if (!fixed && (form.durationMinutes < 30 || form.durationMinutes > 240 || form.durationMinutes % 30 !== 0)) throw new Error('可调度时长必须为30到240分钟之间的30分钟倍数')
  if (!fixed && (minute(form.windowEnd) <= minute(form.windowStart) || form.durationMinutes > minute(form.windowEnd) - minute(form.windowStart))) throw new Error('预约时长不能超过可调度请求窗口')
  return { allocationMode: form.allocationMode, labId: form.labId, date: form.date, startTime: fixed ? form.startTime : null, endTime: fixed ? form.endTime : null, windowStart: fixed ? null : form.windowStart, windowEnd: fixed ? null : form.windowEnd, durationMinutes: fixed ? null : form.durationMinutes, reservationMode: form.reservationMode, attendeeCount: form.attendeeCount, seatCount: form.reservationMode === 'SEAT' ? form.attendeeCount : null, purpose: form.purpose.trim(), preferences: { zoneCode: form.zoneCode.trim() || null, nearWindow: form.nearWindow === '' ? null : form.nearWindow === 'true', minSpecLevel: form.minSpecLevel || null, allowedLabIds: [] } }
}
```

`api/reservations.ts`：

```ts
import { request } from './http'
import type { CreateReservationPayload, PageView, QrCheckinResponse, Reservation, ReservationStatus } from './types'
export function createReservation(data: CreateReservationPayload) { return request<Reservation>('/api/reservations', { method: 'POST', data }) }
export function myReservations(status: ReservationStatus | undefined, page: number, size = 10) { return request<PageView<Reservation>>('/api/reservations/my', { query: { status, page, size } }) }
export function reservationDetail(id: number) { return request<Reservation>(`/api/reservations/${id}`) }
export function cancelReservation(id: number, reason: string | null = null) { return request<Reservation>(`/api/reservations/${id}/cancel`, { method: 'POST', data: { reason } }) }
export function qrCheckin(id: number, content: string) { return request<QrCheckinResponse>('/api/checkin/qr', { method: 'POST', data: { reservationId: id, content } }) }
export function wechatCheckout(id: number) { return request<Reservation>(`/api/checkin/${id}/checkout`, { method: 'POST', data: { channel: 'WECHAT_QR' } }) }
```

Run: `npm test -- --run tests/reservation-form.spec.ts`
Expected: PASS。

- [ ] **Step 3: 实现预约表单页面**

```ts
import { createReservation } from '../../api/reservations'
import { errorMessage } from '../../api/http'
import { restoreSession } from '../../auth/session'
import { buildReservationPayload } from '../../utils/reservation-form'
Page({
  data: { allocationMode: 'FIXED' as 'FIXED' | 'SCHEDULABLE', labId: 0, date: '', startTime: '09:00', endTime: '10:00', windowStart: '08:00', windowEnd: '12:00', durationMinutes: 60, reservationMode: 'SEAT' as 'ROOM' | 'SEAT', attendeeCount: 1, purpose: '', zoneCode: '', nearWindow: '' as '' | 'true' | 'false', minSpecLevel: '', specOptions: ['不限', 'STANDARD', 'HIGH', 'GPU'], specIndex: 0, windowOptions: ['不限', '靠窗', '不靠窗'], windowIndex: 0, durationOptions: [30, 60, 90, 120, 150, 180, 210, 240], durationIndex: 1, submitting: false, error: '', success: '' },
  async onLoad(query: Record<string, string>) {
    if (!await restoreSession()) return
    this.setData({ labId: Number(query.labId), date: query.date ?? '', startTime: (query.startTime ?? '09:00').slice(0, 5), endTime: (query.endTime ?? '10:00').slice(0, 5), reservationMode: query.mode === 'ROOM' ? 'ROOM' : 'SEAT' })
  },
  onAllocation(event: WechatMiniprogram.TouchEvent) { this.setData({ allocationMode: event.currentTarget.dataset.value }) },
  onMode(event: WechatMiniprogram.TouchEvent) { this.setData({ reservationMode: event.currentTarget.dataset.value }) },
  onText(event: WechatMiniprogram.Input) { this.setData({ [event.currentTarget.dataset.field]: event.detail.value }) },
  onCount(event: WechatMiniprogram.Input) { this.setData({ attendeeCount: Number(event.detail.value) }) },
  onSpec(event: WechatMiniprogram.PickerChange) { const specIndex = Number(event.detail.value); this.setData({ specIndex, minSpecLevel: specIndex === 0 ? '' : this.data.specOptions[specIndex] }) },
  onWindow(event: WechatMiniprogram.PickerChange) { const windowIndex = Number(event.detail.value); this.setData({ windowIndex, nearWindow: windowIndex === 0 ? '' : windowIndex === 1 ? 'true' : 'false' }) },
  onDuration(event: WechatMiniprogram.PickerChange) { const durationIndex = Number(event.detail.value); this.setData({ durationIndex, durationMinutes: this.data.durationOptions[durationIndex] }) },
  async submit() {
    if (this.data.submitting) return
    this.setData({ submitting: true, error: '', success: '' })
    try {
      const created = await createReservation(buildReservationPayload(this.data))
      this.setData({ success: `预约 #${created.resvId} 已提交` })
      wx.redirectTo({ url: `/pages/reservation-detail/index?id=${created.resvId}` })
    } catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ submitting: false }) }
  },
})
```

WXML 使用以下完整结构，冲突时不清空 `data`：

```xml
<view class="page"><view class="page-title">确认预约</view><view class="muted page-subtitle">最终可用性由后端在提交时重新校验</view><view class="card form-card"><view class="segment"><text class="{{allocationMode === 'FIXED' ? 'active' : ''}}" data-value="FIXED" bindtap="onAllocation">固定时段</text><text class="{{allocationMode === 'SCHEDULABLE' ? 'active' : ''}}" data-value="SCHEDULABLE" bindtap="onAllocation">可调度</text></view><view class="form-row"><text>日期</text><text>{{date}}</text></view><view wx:if="{{allocationMode === 'FIXED'}}" class="form-row"><text>正式时段</text><text>{{startTime}}–{{endTime}}</text></view><view wx:else><view class="form-row"><text>需求窗口</text><input data-field="windowStart" value="{{windowStart}}" bindinput="onText" /><input data-field="windowEnd" value="{{windowEnd}}" bindinput="onText" /></view><picker range="{{durationOptions}}" value="{{durationIndex}}" bindchange="onDuration"><view class="form-row"><text>所需时长</text><text>{{durationMinutes}} 分钟</text></view></picker></view><view class="segment"><text class="{{reservationMode === 'ROOM' ? 'active' : ''}}" data-value="ROOM" bindtap="onMode">整室</text><text class="{{reservationMode === 'SEAT' ? 'active' : ''}}" data-value="SEAT" bindtap="onMode">工位</text></view><view class="form-row"><text>使用人数</text><input type="number" value="{{attendeeCount}}" bindinput="onCount" /></view><view wx:if="{{reservationMode === 'SEAT'}}" class="muted form-note">工位数与人数一致，提交后由系统正式分配</view><picker range="{{specOptions}}" value="{{specIndex}}" bindchange="onSpec"><view class="form-row"><text>最低配置</text><text>{{specOptions[specIndex]}}</text></view></picker><picker range="{{windowOptions}}" value="{{windowIndex}}" bindchange="onWindow"><view class="form-row"><text>靠窗偏好</text><text>{{windowOptions[windowIndex]}}</text></view></picker><view class="form-row"><text>偏好区域</text><input placeholder="可留空" data-field="zoneCode" value="{{zoneCode}}" bindinput="onText" /></view><textarea class="purpose" maxlength="500" placeholder="使用用途（必填）" data-field="purpose" value="{{purpose}}" bindinput="onText" /></view><view wx:if="{{error}}" class="error">{{error}}</view><view wx:if="{{success}}" class="success">{{success}}</view><button class="button-primary" loading="{{submitting}}" disabled="{{submitting}}" bindtap="submit">确认提交预约</button></view>
```

WXSS：

```css
.page-title { font-size: 40rpx; font-weight: 700; }.page-subtitle { margin: 10rpx 0 28rpx; }.form-card { padding: 26rpx; }.segment { display: flex; margin-bottom: 22rpx; padding: 6rpx; background: #e9edf4; border-radius: 14rpx; }.segment text { flex: 1; padding: 14rpx; color: #77849a; text-align: center; }.segment .active { color: #3867e8; background: #fff; border-radius: 10rpx; }.form-row { display: flex; min-height: 84rpx; align-items: center; justify-content: space-between; gap: 20rpx; border-bottom: 2rpx solid #edf0f5; }.form-row input { flex: 1; text-align: right; }.form-note { margin: -10rpx 0 20rpx; font-size: 23rpx; }.purpose { width: 100%; min-height: 160rpx; margin-top: 24rpx; padding: 20rpx; background: #f8faff; border-radius: 14rpx; }.success { margin-bottom: 20rpx; padding: 22rpx; color: #107c5c; background: #edf8f4; border-radius: 14rpx; }
```

JSON：`{ "navigationBarTitleText": "新建预约" }`。把 `pages/reservation-form/index` 加入 `app.json.pages`，不要放入 tabBar。

- [ ] **Step 4: 运行检查并提交**

Run: `npm run typecheck && npm test`
Expected: PASS；409 后表单值保留，提交中按钮禁用。

```bash
git add miniprogram/miniprogram/api/reservations.ts miniprogram/miniprogram/utils/reservation-form.ts miniprogram/miniprogram/pages/reservation-form miniprogram/miniprogram/app.json miniprogram/tests/reservation-form.spec.ts
git commit -m "feat: submit reservations from mini program"
```

### Task 11: 可测试的扫码签到、取消与微信签退动作

**Files:**
- Create: `miniprogram/miniprogram/actions/reservations.ts`
- Test: `miniprogram/tests/reservation-actions.spec.ts`

- [ ] **Step 1: 写扫码成功、取消扫码和签到失败测试**

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('../miniprogram/api/reservations', () => ({ qrCheckin: vi.fn() }))
import { qrCheckin } from '../miniprogram/api/reservations'
import { scanAndCheckin } from '../miniprogram/actions/reservations'
describe('scan and check-in', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.stubGlobal('wx', { scanCode: vi.fn(), showToast: vi.fn() }) })
  it('posts the raw QR result for the selected reservation', async () => {
    vi.mocked(wx.scanCode).mockImplementation(options => options.success?.({ result: 'signed-code' } as never))
    vi.mocked(qrCheckin).mockResolvedValue({ alreadyCheckedIn: false } as never)
    await expect(scanAndCheckin(12)).resolves.toBe(true)
    expect(qrCheckin).toHaveBeenCalledWith(12, 'signed-code')
  })
  it('does not call API when the user cancels scanning', async () => {
    vi.mocked(wx.scanCode).mockImplementation(options => options.fail?.({ errMsg: 'scanCode:fail cancel' }))
    await expect(scanAndCheckin(12)).resolves.toBe(false)
    expect(qrCheckin).not.toHaveBeenCalled()
  })
  it('surfaces server rejection without changing local state', async () => {
    vi.mocked(wx.scanCode).mockImplementation(options => options.success?.({ result: 'forged' } as never))
    vi.mocked(qrCheckin).mockRejectedValue(new Error('二维码无效'))
    await expect(scanAndCheckin(12)).rejects.toThrow('二维码无效')
  })
})
```

Run: `npm test -- --run tests/reservation-actions.spec.ts`
Expected: FAIL，动作模块不存在。

- [ ] **Step 2: 实现共享动作**

```ts
import { qrCheckin, wechatCheckout } from '../api/reservations'
export function scanAndCheckin(reservationId: number): Promise<boolean> {
  return new Promise((resolve, reject) => {
    wx.scanCode({
      onlyFromCamera: true, scanType: ['qrCode'],
      success: async ({ result }) => {
        try {
          const response = await qrCheckin(reservationId, result)
          wx.showToast({ title: response.alreadyCheckedIn ? '已完成签到' : '签到成功', icon: 'success' })
          resolve(true)
        } catch (error) { reject(error) }
      },
      fail: ({ errMsg }) => errMsg.includes('cancel') ? resolve(false) : reject(new Error('扫码失败，请重试')),
    })
  })
}
export async function checkoutFromWeChat(reservationId: number) {
  const result = await wechatCheckout(reservationId)
  wx.showToast({ title: '已签退', icon: 'success' })
  return result
}
```

- [ ] **Step 3: 运行检查并提交**

Run: `npm run typecheck && npm test -- --run tests/reservation-actions.spec.ts`
Expected: PASS。

```bash
git add miniprogram/miniprogram/actions/reservations.ts miniprogram/tests/reservation-actions.spec.ts
git commit -m "feat: add mini program scan check-in action"
```

### Task 12: 预约记录、详情、取消和签退页面

**Files:**
- Create: `miniprogram/miniprogram/pages/reservations/index.ts、index.wxml、index.wxss、index.json`
- Create: `miniprogram/miniprogram/pages/reservation-detail/index.ts、index.wxml、index.wxss、index.json`
- Modify: `miniprogram/miniprogram/app.json`

- [ ] **Step 1: 实现记录页的筛选与分页逻辑**

```ts
import { myReservations } from '../../api/reservations'
import { errorMessage } from '../../api/http'
import { restoreSession } from '../../auth/session'
import type { Reservation, ReservationStatus } from '../../api/types'
import { reservationTime, statusLabels } from '../../utils/format'
Page({
  data: { items: [] as Array<Reservation & { timeText: string; statusText: string; canCancel: boolean }>, labels: statusLabels, status: '' as ReservationStatus | '', filters: [{ value: '', label: '全部' }, { value: 'APPROVED', label: '待使用' }, { value: 'IN_USE', label: '使用中' }, { value: 'COMPLETED', label: '已完成' }], page: 0, total: 0, loading: false, error: '' },
  async onShow() { if (await restoreSession()) await this.reload() },
  async reload() { this.setData({ page: 0, items: [] }); await this.loadMore() },
  async loadMore() {
    if (this.data.loading || this.data.page > 0 && this.data.items.length >= this.data.total) return
    this.setData({ loading: true, error: '' })
    try {
      const result = await myReservations(this.data.status || undefined, this.data.page)
      const items = result.items.map(item => ({ ...item, timeText: reservationTime(item), statusText: statusLabels[item.status], canCancel: ['PENDING_SCHEDULE', 'APPROVED'].includes(item.status) }))
      this.setData({ items: this.data.items.concat(items), page: this.data.page + 1, total: result.total })
    } catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ loading: false }) }
  },
  onReachBottom() { void this.loadMore() },
  onFilter(event: WechatMiniprogram.TouchEvent) { this.setData({ status: event.currentTarget.dataset.value }); void this.reload() },
  open(event: WechatMiniprogram.TouchEvent) { wx.navigateTo({ url: `/pages/reservation-detail/index?id=${event.currentTarget.dataset.id}` }) },
})
```

- [ ] **Step 2: 实现记录页 WXML/WXSS/JSON**

```xml
<view class="page"><view class="page-title">我的预约</view><view class="filters"><text wx:for="{{filters}}" wx:key="value" class="{{status === item.value ? 'active' : ''}}" data-value="{{item.value}}" bindtap="onFilter">{{item.label}}</text></view><view wx:if="{{error}}" class="error">{{error}}<text bindtap="reload">重试</text></view><view wx:if="{{!items.length && !loading}}" class="empty">没有符合条件的预约</view><view wx:for="{{items}}" wx:key="resvId" class="card reservation" data-id="{{item.resvId}}" bindtap="open"><view class="reservation__top"><text class="reservation__lab">{{item.labName || '待排期分配'}}</text><text class="status status--{{item.status}}">{{item.statusText}}</text></view><view class="reservation__time">{{item.timeText}}</view><view class="muted reservation__meta">{{labels[item.reservationMode]}} · {{item.attendeeCount}} 人<text wx:if="{{item.seats.length}}"> · {{item.seats[0].seatCode}}{{item.seats.length > 1 ? ' 等' : ''}}</text></view></view><view wx:if="{{loading}}" class="loading">正在加载预约记录</view></view>
```

WXML 顶部需加入 `var statusLabels` 不可直接访问 TS 模块，故在 `data` 增加 `labels: statusLabels`，模板使用 `labels[item.reservationMode]`。WXSS：

```css
.page-title { margin-bottom: 24rpx; font-size: 40rpx; font-weight: 700; }.filters { display: flex; gap: 28rpx; margin-bottom: 24rpx; }.filters text { padding-bottom: 12rpx; color: #77849a; }.filters .active { color: #3867e8; border-bottom: 4rpx solid #3867e8; }.reservation { margin-bottom: 18rpx; padding: 28rpx; }.reservation__top { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }.reservation__lab { font-weight: 650; }.reservation__time { margin-top: 18rpx; font-size: 30rpx; font-weight: 600; }.reservation__meta { margin-top: 12rpx; font-size: 24rpx; }.status { padding: 8rpx 16rpx; color: #627089; background: #f1f3f6; border-radius: 30rpx; font-size: 21rpx; }.status--APPROVED, .status--IN_USE { color: #108260; background: #eaf8f2; }.status--NO_SHOW, .status--VIOLATION_TERMINATED { color: #c65361; background: #fff0f1; }
```

JSON：`{ "navigationBarTitleText": "我的预约", "onReachBottomDistance": 80 }`。

- [ ] **Step 3: 实现详情页及操作**

```ts
import { cancelReservation, reservationDetail } from '../../api/reservations'
import { errorMessage } from '../../api/http'
import { restoreSession } from '../../auth/session'
import { scanAndCheckin, checkoutFromWeChat } from '../../actions/reservations'
import type { Reservation } from '../../api/types'
import { formatDateTime, reservationTime, statusLabels } from '../../utils/format'
Page({
  data: { id: 0, detail: null as Reservation | null, labels: statusLabels, timeText: '', loading: false, actionLoading: false, error: '', seatText: '', checkinText: '—', checkoutText: '—' },
  async onLoad(query: Record<string, string>) { this.setData({ id: Number(query.id) }); if (await restoreSession()) await this.load() },
  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const detail = await reservationDetail(this.data.id)
      this.setData({ detail, timeText: reservationTime(detail), seatText: detail.seats.map(seat => seat.seatCode).join('、'), checkinText: formatDateTime(detail.checkinTime), checkoutText: formatDateTime(detail.checkoutTime) })
    } catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ loading: false }) }
  },
  async scan() { this.setData({ actionLoading: true, error: '' }); try { if (await scanAndCheckin(this.data.id)) await this.load() } catch (error) { this.setData({ error: errorMessage(error) }) } finally { this.setData({ actionLoading: false }) } },
  async cancel() {
    const confirmed = await wx.showModal({ title: '取消预约', content: '取消后时段将被释放，临近开始取消可能产生违规记录。', confirmText: '确认取消' })
    if (!confirmed.confirm) return
    this.setData({ actionLoading: true, error: '' })
    try { await cancelReservation(this.data.id); await this.load() } catch (error) { this.setData({ error: errorMessage(error) }) } finally { this.setData({ actionLoading: false }) }
  },
  async checkout() {
    const confirmed = await wx.showModal({ title: '提前签退', content: '仅释放当前之后尚未开始的完整时间片。', confirmText: '确认签退' })
    if (!confirmed.confirm) return
    this.setData({ actionLoading: true, error: '' })
    try { await checkoutFromWeChat(this.data.id); await this.load() } catch (error) { this.setData({ error: errorMessage(error) }) } finally { this.setData({ actionLoading: false }) }
  },
})
```

WXML：

```xml
<view class="page"><view wx:if="{{error}}" class="error">{{error}}</view><view wx:if="{{loading && !detail}}" class="loading">正在读取预约详情</view><view wx:if="{{detail}}" class="card detail"><view class="detail__head"><text>{{detail.labName || '待排期分配'}}</text><text class="status">{{labels[detail.status]}}</text></view><view class="detail__time">{{timeText}}</view><view class="detail-row"><text>预约方式</text><text>{{labels[detail.allocationMode]}} · {{labels[detail.reservationMode]}}</text></view><view class="detail-row"><text>人数 / 工位</text><text>{{detail.attendeeCount}} / {{detail.seatCount || '—'}}</text></view><view class="detail-row"><text>正式座位</text><text>{{seatText || '尚未分配'}}</text></view><view class="detail-row"><text>用途</text><text>{{detail.purpose}}</text></view><view class="detail-row"><text>签到</text><text>{{checkinText}}</text></view><view class="detail-row"><text>签退</text><text>{{checkoutText}}</text></view></view><view wx:if="{{detail}}" class="actions"><button wx:if="{{detail.status === 'APPROVED'}}" class="button-primary" disabled="{{actionLoading}}" bindtap="scan">扫码签到</button><button wx:if="{{detail.status === 'IN_USE'}}" class="button-primary" disabled="{{actionLoading}}" bindtap="checkout">提前签退</button><button wx:if="{{detail.status === 'APPROVED' || detail.status === 'PENDING_SCHEDULE'}}" class="button-secondary" disabled="{{actionLoading}}" bindtap="cancel">取消预约</button></view></view>
```

详情 WXSS：

```css
.detail { padding: 30rpx; }.detail__head { display: flex; justify-content: space-between; font-weight: 650; }.detail__time { margin: 24rpx 0; font-size: 32rpx; font-weight: 700; }.detail-row { display: flex; justify-content: space-between; gap: 28rpx; padding: 20rpx 0; border-bottom: 2rpx solid #edf0f5; }.detail-row text:last-child { text-align: right; }.actions { margin-top: 24rpx; }.actions button { margin-bottom: 16rpx; }
```

JSON：`{ "navigationBarTitleText": "预约详情" }`。

把 `pages/reservations/index` 与 `pages/reservation-detail/index` 加入 `app.json.pages`。

- [ ] **Step 4: 运行检查并提交**

Run: `npm run typecheck && npm test`
Expected: PASS。取消/签退均先确认，操作后重新读取真实详情。

```bash
git add miniprogram/miniprogram/pages/reservations miniprogram/miniprogram/pages/reservation-detail miniprogram/miniprogram/app.json
git commit -m "feat: manage reservations in mini program"
```

### Task 13: 首页下一场预约与个人中心

**Files:**
- Create: `miniprogram/miniprogram/utils/reservations.ts`
- Modify: `miniprogram/miniprogram/pages/home/index.ts、index.wxml、index.wxss、index.json`
- Modify: `miniprogram/miniprogram/pages/profile/index.ts、index.wxml、index.wxss、index.json`
- Test: `miniprogram/tests/reservations.spec.ts`

- [ ] **Step 1: 写下一场预约优先级失败测试**

```ts
import { describe, expect, it } from 'vitest'
import { nextReservation } from '../miniprogram/utils/reservations'
import type { Reservation } from '../miniprogram/api/types'
const reservation = (resvId: number, status: Reservation['status'], startTime: string | null) => ({ resvId, status, startTime }) as Reservation
describe('next reservation', () => {
  it('prioritizes in-use then nearest approved and ignores expired completed records', () => {
    expect(nextReservation([reservation(1, 'APPROVED', '2026-11-08T14:00:00'), reservation(2, 'IN_USE', '2026-11-07T08:00:00')], '2026-11-07T09:00:00')?.resvId).toBe(2)
    expect(nextReservation([reservation(1, 'APPROVED', '2026-11-08T14:00:00'), reservation(2, 'APPROVED', '2026-11-07T10:00:00')], '2026-11-07T09:00:00')?.resvId).toBe(2)
    expect(nextReservation([reservation(3, 'COMPLETED', '2026-11-07T10:00:00')], '2026-11-07T09:00:00')).toBeNull()
  })
})
```

Run: `npm test -- --run tests/reservations.spec.ts`
Expected: FAIL，模块不存在。

- [ ] **Step 2: 实现选择逻辑与首页**

`utils/reservations.ts`：

```ts
import type { Reservation } from '../api/types'
export function nextReservation(items: Reservation[], nowIso: string): Reservation | null {
  const inUse = items.find(item => item.status === 'IN_USE')
  if (inUse) return inUse
  return items.filter(item => item.status === 'APPROVED' && item.startTime && item.startTime >= nowIso.slice(0, 16))
    .sort((a, b) => (a.startTime ?? '').localeCompare(b.startTime ?? ''))[0] ?? null
}
```

首页 TS：

```ts
import { myReservations } from '../../api/reservations'
import { errorMessage } from '../../api/http'
import { restoreSession } from '../../auth/session'
import { readSession } from '../../auth/storage'
import { scanAndCheckin } from '../../actions/reservations'
import { nextReservation } from '../../utils/reservations'
import { reservationTime, statusLabels, toDateInput } from '../../utils/format'
import type { Reservation } from '../../api/types'
Page({
  data: { userName: '', next: null as Reservation | null, nextTime: '', nextSeats: '', nextStatus: '', recent: [] as Array<Reservation & { timeText: string; statusText: string }>, loading: false, actionLoading: false, error: '' },
  async onShow() { if (await restoreSession()) { this.setData({ userName: readSession()?.user.name ?? '' }); await this.load() } },
  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const [approved, inUse, pending] = await Promise.all([myReservations('APPROVED', 0, 100), myReservations('IN_USE', 0, 20), myReservations('PENDING_SCHEDULE', 0, 5)])
      const now = `${toDateInput(new Date())}T${String(new Date().getHours()).padStart(2, '0')}:${String(new Date().getMinutes()).padStart(2, '0')}`
      const next = nextReservation([...inUse.items, ...approved.items], now)
      this.setData({ next, nextTime: next ? reservationTime(next) : '', nextSeats: next?.seats.map(seat => seat.seatCode).join('、') ?? '', nextStatus: next ? statusLabels[next.status] : '', recent: pending.items.map(item => ({ ...item, timeText: reservationTime(item), statusText: statusLabels[item.status] })) })
    } catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ loading: false }) }
  },
  labs() { wx.switchTab({ url: '/pages/labs/index' }) },
  reservations() { wx.navigateTo({ url: '/pages/reservations/index' }) },
  openNext() { if (this.data.next) wx.navigateTo({ url: `/pages/reservation-detail/index?id=${this.data.next.resvId}` }) },
  async scanNext() {
    const next = this.data.next
    if (!next || this.data.actionLoading) return
    this.setData({ actionLoading: true, error: '' })
    try { if (await scanAndCheckin(next.resvId)) await this.load() }
    catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ actionLoading: false }) }
  },
})
```

首页 WXML：

```xml
<view class="page"><view class="brand"><text class="brand__mark">L</text><text class="brand__name">LabSpace</text></view><view class="muted greeting">你好，{{userName}}</view><view class="headline">安排今天的实验</view><view wx:if="{{error}}" class="error">{{error}}</view><view wx:if="{{next}}" class="next-card" bindtap="openNext"><view class="next-card__top"><text>下一场预约</text><text>{{nextStatus}}</text></view><view class="next-card__lab">{{next.labName}}</view><view class="next-card__time">{{nextTime}}</view><view class="next-card__seat">{{nextSeats || '整室预约或等待分配'}}</view><button class="scan-button" disabled="{{actionLoading}}" catchtap="scanNext">扫码签到</button></view><view wx:elif="{{!loading}}" class="card empty">暂无待使用预约，去实验室选择时段</view><view class="quick-grid"><button class="card quick" bindtap="labs">预约实验室<text>查看实时空闲时段</text></button><button class="card quick" bindtap="reservations">我的预约<text>详情、取消与签退</text></button></view><view wx:if="{{recent.length}}" class="section-title">待排期需求</view><view wx:for="{{recent}}" wx:key="resvId" class="card recent"><view>{{item.labName || '待排期分配'}}<text>{{item.statusText}}</text></view><view class="muted">{{item.timeText}}</view></view></view>
```

首页 WXSS：

```css
.brand { display:flex; align-items:center; gap:16rpx; }.brand__mark { display:grid; width:58rpx; height:58rpx; color:#fff; background:#3867e8; border-radius:16rpx; place-items:center; }.brand__name { font-size:34rpx; font-weight:750; }.greeting { margin-top:34rpx; }.headline { margin:8rpx 0 28rpx; font-size:44rpx; font-weight:750; }.next-card { padding:32rpx; color:#fff; background:#315fda; border-radius:28rpx; }.next-card__top { display:flex; justify-content:space-between; font-size:24rpx; }.next-card__lab { margin-top:32rpx; font-size:34rpx; font-weight:700; }.next-card__time { margin-top:14rpx; }.next-card__seat { margin:12rpx 0 28rpx; color:#dce6ff; font-size:24rpx; }.scan-button { color:#274da9; background:#fff; border-radius:16rpx; font-size:26rpx; }.quick-grid { display:grid; grid-template-columns:1fr 1fr; gap:20rpx; margin:24rpx 0; }.quick { padding:24rpx; text-align:left; font-size:27rpx; font-weight:650; line-height:1.4; }.quick text { display:block; margin-top:10rpx; color:#77849a; font-size:21rpx; font-weight:400; }.section-title { margin:30rpx 0 16rpx; font-weight:650; }.recent { margin-bottom:14rpx; padding:24rpx; }.recent>view:first-child { display:flex; justify-content:space-between; margin-bottom:10rpx; }
```

- [ ] **Step 3: 实现个人中心**

```ts
import { restoreSession } from '../../auth/session'
import { clearSession, readSession } from '../../auth/storage'
import { isDevelopmentLoginVisible } from '../../config/index'
import type { User } from '../../api/types'
Page({
  data: { user: null as User | null, roleText: '', development: false },
  async onShow() { if (await restoreSession()) { const user = readSession()?.user ?? null; this.setData({ user, development: isDevelopmentLoginVisible(), roleText: user?.roles.map(role => ({ STUDENT: '学生', TEACHER: '教师', LAB_ADMIN: '实验室管理员', SYS_ADMIN: '系统管理员' })[role]).join(' / ') ?? '' }) } },
  reservations() { wx.navigateTo({ url: '/pages/reservations/index' }) },
  async logout() { const result = await wx.showModal({ title: '退出登录', content: '退出后需要重新验证身份。' }); if (result.confirm) { clearSession(); wx.reLaunch({ url: '/pages/login/index' }) } },
})
```

WXML：`<view class="page"><view class="card profile"><view class="avatar">{{user.name[0]}}</view><view><view class="profile__name">{{user.name}}</view><view class="muted">{{user.account}} · {{roleText}}</view><view wx:if="{{development}}" class="muted development-note">当前为开发登录环境</view></view></view><button class="card profile-link" bindtap="reservations">我的预约<text>›</text></button><button class="button-secondary logout" bindtap="logout">退出登录</button></view>`。
WXSS：`.profile { display:flex; align-items:center; gap:24rpx; padding:32rpx; }.avatar { display:grid; width:84rpx; height:84rpx; color:#3867e8; background:#eaf0ff; border-radius:50%; place-items:center; font-size:32rpx; }.profile__name { margin-bottom:10rpx; font-size:34rpx; font-weight:700; }.development-note { margin-top:8rpx; font-size:22rpx; }.profile-link { display:flex; justify-content:space-between; width:100%; margin-top:24rpx; padding:28rpx; text-align:left; }.logout { margin-top:36rpx; }`。
JSON：`{ "navigationBarTitleText": "我的" }`。

- [ ] **Step 4: 运行检查并提交**

Run: `npm run typecheck && npm test`
Expected: PASS。首页只展示真实 API 预约，个人中心不显示密码、openid 或令牌。

```bash
git add miniprogram/miniprogram/pages/home miniprogram/miniprogram/pages/profile miniprogram/miniprogram/utils/reservations.ts miniprogram/tests/reservations.spec.ts
git commit -m "feat: add mini program home and profile"
```

### Task 14: 本地签到联调资源、端到端验收与全量回归

**Files:**
- Modify: `server/src/main/java/com/campus/lab/config/LocalDemoData.java`
- Test: `server/src/test/java/com/campus/lab/LocalDemoDataIntegrationTest.java`
- Verify: `miniprogram/` 全工程与现有 `web/`、`server/`

- [ ] **Step 1: 写本地签到实验室失败测试**

在 `LocalDemoDataIntegrationTest` 添加断言：本地存在 `LAB-CHECKIN-DEMO`，含一个可用工位，七天规则均为 `00:00:00–24:00:00`、`advance_min_days = 0`、`advance_max_days = 14`。使用以下查询，避免改动正常 LAB-A/LAB-B 规则：

```java
@Test
void localCheckinLaboratoryAllowsSameDayTestingWithoutChangingNormalLabs() {
    assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM t_lab WHERE lab_code = 'LAB-CHECKIN-DEMO'", Integer.class))
            .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_lab_seat s JOIN t_lab l ON l.lab_id = s.lab_id
            WHERE l.lab_code = 'LAB-CHECKIN-DEMO' AND s.seat_status = 'ACTIVE'
            """, Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_time_rule r JOIN t_lab l ON l.lab_id = r.lab_id
            WHERE l.lab_code = 'LAB-CHECKIN-DEMO' AND r.open_time = '00:00:00'
              AND r.close_time = '24:00:00' AND r.advance_min_days = 0 AND r.advance_max_days = 14
            """, Integer.class)).isEqualTo(7);
    assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_time_rule r JOIN t_lab l ON l.lab_id = r.lab_id
            WHERE l.lab_code = 'LAB-A' AND r.advance_min_days = 1
            """, Integer.class)).isEqualTo(7);
}
```

Run: `mvn -Dtest=LocalDemoDataIntegrationTest#localCheckinLaboratoryAllowsSameDayTestingWithoutChangingNormalLabs test`
Expected: FAIL，联调实验室不存在。

- [ ] **Step 2: 仅在 local profile 添加独立联调资源**

在 `LocalDemoData.run` 调用 `createCheckinDemoLaboratory()`，新增方法：

```java
private void createCheckinDemoLaboratory() {
    long labId = ensureLab("LAB-CHECKIN-DEMO", "签到联调实验室（本地）", "本地测试环境", 1);
    jdbcTemplate.update("""
            INSERT INTO t_lab_seat
                (lab_id, seat_code, zone_code, seat_index, spec_level, near_window, seat_status)
            VALUES (?, 'CHECKIN-SEAT-01', 'TEST', 1, 'STANDARD', FALSE, 'ACTIVE')
            ON DUPLICATE KEY UPDATE seat_id = t_lab_seat.seat_id
            """, labId);
    for (int day = 1; day <= 7; day++) {
        jdbcTemplate.update("""
                INSERT INTO t_time_rule
                    (lab_id, day_of_week, open_time, close_time, advance_min_days, advance_max_days)
                VALUES (?, ?, '00:00:00', '24:00:00', 0, 14)
                ON DUPLICATE KEY UPDATE rule_id = t_time_rule.rule_id
                """, labId, day);
    }
}
```

Run: `mvn -Dtest=LocalDemoDataIntegrationTest,AuthIntegrationTest test`
Expected: PASS，演示账号与普通实验室规则不变。

- [ ] **Step 3: 运行所有自动化检查**

Run in `server/`: `mvn test`
Expected: `BUILD SUCCESS`。

Run in `miniprogram/`: `npm run typecheck && npm test`
Expected: 所有测试 PASS，开发者工具编译无错误。

Run in `web/`: `npm run build && npm test`
Expected: Vue 类型检查、Vite 构建和既有 Vitest 全部 PASS。

- [ ] **Step 4: 启动真实本地服务并验证预约/取消闭环**

Run from project root: `powershell -ExecutionPolicy Bypass -File scripts/start-dev.ps1`。
Expected: 8080、8000、5173 就绪。小程序开发者工具导入 `miniprogram/project.config.json`，开发登录使用现有本地演示学生账号。依次验证：

1. 登录后首页调用 `/api/auth/me`，个人中心显示当前学生。
2. 实验室选择普通实验室和明天日期，选择连续空闲时段，提交工位预约。
3. 详情显示 `APPROVED` 和后端正式座位；记录页可见同一预约。
4. 取消后详情为 `CANCELLED`，实验室相应时间片恢复余量。
5. 断网时身份不被清除；恢复后可重试。令牌失效时回到登录页。

- [ ] **Step 5: 验证扫码签到与微信签退闭环**

用系统管理员账号调用 `GET /api/admin/resources/labs/{labId}/checkin-code` 获取联调实验室签到码文本。在开发者工具中模拟 `wx.scanCode` 返回该文本，依次验证：

1. 在签到联调实验室创建覆盖当前时间、位于签到窗口内的预约。
2. 从预约详情扫码，状态变为 `IN_USE`，`checkinChannel = WECHAT_QR`。
3. 重复扫码返回 `alreadyCheckedIn = true`，数据库签到日志仍只有一条。
4. 提前签退后状态为 `COMPLETED`，`checkoutChannel = WECHAT_QR`。
5. 伪造签名、错误实验室、他人预约和窗口外请求分别被 400/409/404/409 拒绝，原状态不变。
6. 既有网页签退不带 body 仍记录 `WEB_ACCOUNT`，终端签到仍记录 `TERMINAL_ACCOUNT`。

- [ ] **Step 6: 明确外部平台验证边界并清理运行产物**

真实 `wx.login` 只有在有效 AppID/AppSecret 下验证；真机扫码需要 HTTPS 合法域名和真机。条件不齐时在交付说明中列为未实测，不能用开发登录或模拟扫码替代声明。

Run `git status --short`（仅当已是仓库），删除本次运行产生且不需要交付的日志/临时输出；保留 `package-lock.json`、测试和设计文档。不删除用户原有文件。

- [ ] **Step 7: 提交最终变更（仅在仓库与授权存在时）**

```bash
git add server/src/main/java/com/campus/lab/config/LocalDemoData.java server/src/test/java/com/campus/lab/LocalDemoDataIntegrationTest.java miniprogram docs/superpowers/specs/2026-11-07-wechat-miniprogram-core-design.md docs/superpowers/plans/2026-11-07-wechat-miniprogram-core.md
git commit -m "feat: deliver WeChat mini program reservation loop"
```
