package io.guestgraph.ingest;

public class UnknownSourceSystemException extends RuntimeException {

    public UnknownSourceSystemException(String code) {
        super("Source system '" + code + "' is not registered in this tenant");
    }
}
