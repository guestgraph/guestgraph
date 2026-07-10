package io.guestgraph.resolution;

/** The requested unmerge is not executable (single-record guest, unlinked record, ...). */
public class InvalidUnmergeException extends RuntimeException {

  public InvalidUnmergeException(String message) {
    super(message);
  }
}
