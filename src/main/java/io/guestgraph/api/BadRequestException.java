package io.guestgraph.api;

public class BadRequestException extends RuntimeException {

    public BadRequestException(String detail) {
        super(detail);
    }
}
