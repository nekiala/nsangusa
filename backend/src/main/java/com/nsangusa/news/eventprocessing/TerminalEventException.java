package com.nsangusa.news.eventprocessing;

/** An event violates an invariant that redelivery cannot repair. */
public class TerminalEventException extends RuntimeException {
  public TerminalEventException(String message) {
    super(message);
  }
}
