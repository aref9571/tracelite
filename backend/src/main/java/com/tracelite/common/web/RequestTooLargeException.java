package com.tracelite.common.web;

import java.io.IOException;

public class RequestTooLargeException extends IOException {

    private final long maxBytes;

    public RequestTooLargeException(long maxBytes) {
        super("Request body exceeds " + maxBytes + " bytes");
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}
