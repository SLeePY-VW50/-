package com.campus.lab.credit.dto;

import com.campus.lab.reservation.dto.PageView;

public record CreditAccountView(int creditScore, PageView<CreditRecordView> records) {
}
