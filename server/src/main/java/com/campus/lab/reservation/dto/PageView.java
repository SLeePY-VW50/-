package com.campus.lab.reservation.dto;

import java.util.List;

public record PageView<T>(List<T> items, int page, int size, long total) {

    public PageView {
        items = List.copyOf(items);
    }
}
