package com.campus.lab.checkin.qr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.lab.auth.wechat.WeChatProperties;
import com.campus.lab.common.BusinessException;
import java.util.Base64;
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

    @Test
    void onlyCanonicalVersionOneContentIsAccepted() {
        String content = service.contentForLab(42);
        String signature = content.substring(content.indexOf("&signature=") + 11);

        assertThatThrownBy(() -> service.verify(content.replace("v=1", "v=2")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.verify(content.replace("v=1&labId=42&", "labId=42&v=1&")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.verify(content + "&labId=42"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.verify(content + "#fragment"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.verify(content.replace("labId", "lab%49d")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.verify(content + "="))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.verify(
                "labspace://checkin?v=1&labId=0&signature=" + signature))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.verify(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void nonCanonicalSignaturePaddingBitsAreRejected() {
        String content = service.contentForLab(42);
        int signatureStart = content.indexOf("&signature=") + 11;
        String signature = content.substring(signatureStart);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char last = signature.charAt(signature.length() - 1);
        char modifiedLast = alphabet.charAt(alphabet.indexOf(last) ^ 3);
        String nonCanonicalSignature = signature.substring(0, signature.length() - 1) + modifiedLast;
        String nonCanonicalContent = content.substring(0, signatureStart) + nonCanonicalSignature;

        assertThat(Base64.getUrlDecoder().decode(nonCanonicalSignature))
                .isEqualTo(Base64.getUrlDecoder().decode(signature));
        assertThatThrownBy(() -> service.verify(nonCanonicalContent))
                .isInstanceOf(BusinessException.class).hasMessage("二维码无效");
    }

    @Test
    void secretShorterThanThirtyTwoBytesCannotSign() {
        CheckinQrService shortSecretService = new CheckinQrService(
                new WeChatProperties("wx", "secret", "too-short"));

        assertThatThrownBy(() -> shortSecretService.contentForLab(42))
                .isInstanceOf(IllegalStateException.class).hasMessage("签到二维码密钥未配置");
    }
}
