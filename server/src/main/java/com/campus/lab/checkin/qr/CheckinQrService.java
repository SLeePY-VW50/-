package com.campus.lab.checkin.qr;

import com.campus.lab.auth.wechat.WeChatProperties;
import com.campus.lab.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CheckinQrService {

    private static final Pattern CONTENT_PATTERN = Pattern.compile(
            "labspace://checkin\\?v=1&labId=([1-9][0-9]*)&signature=([A-Za-z0-9_-]{43})");

    private final WeChatProperties properties;

    public CheckinQrService(WeChatProperties properties) {
        this.properties = properties;
    }

    public String contentForLab(long labId) {
        if (labId <= 0) {
            throw invalid();
        }
        return "labspace://checkin?v=1&labId=" + labId + "&signature=" + signature(labId);
    }

    public long verify(String content) {
        if (content == null) {
            throw invalid();
        }
        Matcher matcher = CONTENT_PATTERN.matcher(content);
        if (!matcher.matches()) {
            throw invalid();
        }
        try {
            long labId = Long.parseLong(matcher.group(1));
            byte[] provided = matcher.group(2).getBytes(StandardCharsets.US_ASCII);
            byte[] expected = signature(labId).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(provided, expected)) {
                throw invalid();
            }
            return labId;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private String signature(long labId) {
        byte[] secret = properties.checkinQrSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("签到二维码密钥未配置");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(("v=1&labId=" + labId).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("签到二维码签名不可用", exception);
        }
    }

    public static BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "二维码无效");
    }
}
