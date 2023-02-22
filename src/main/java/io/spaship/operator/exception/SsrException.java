package io.spaship.operator.exception;

public class SsrException extends RuntimeException{

    public SsrException() {
        super("Something went wrong while processing the ssr request.");
      }
    
      public SsrException(String details) {
        super(details);
      }
    
}
