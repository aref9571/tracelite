package com.tracelite.event.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record EventPageRequest(
        @Min(0)
        @Max(MAX_PAGE)
        Integer page,

        @Min(1)
        @Max(MAX_PAGE_SIZE)
        Integer size) {

    public static final int MAX_PAGE = 10_000;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    int pageOrDefault() {
        return page == null ? 0 : page;
    }

    int sizeOrDefault() {
        return size == null ? DEFAULT_PAGE_SIZE : size;
    }
}
