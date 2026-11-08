package com.campus.lab.checkin;

public enum CheckinChannel {
    TERMINAL_ACCOUNT("公共终端账号签到"),
    WECHAT_QR("微信扫码签到");

    private final String logReason;

    CheckinChannel(String logReason) {
        this.logReason = logReason;
    }

    public String logReason() {
        return logReason;
    }
}
