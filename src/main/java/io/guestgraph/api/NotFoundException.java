package io.guestgraph.api;

/** Resource absent in the caller's tenant — cross-tenant ids intentionally look identical. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String detail) {
        super(detail);
    }
}
