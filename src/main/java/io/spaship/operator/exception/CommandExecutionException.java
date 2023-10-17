package io.spaship.operator.exception;

public class CommandExecutionException extends Exception{
    public CommandExecutionException(String message) {
        super(message);
    }

    public CommandExecutionException() {
        super();
    }

    public CommandExecutionException(Throwable cause) {
        super(cause);
    }

    public CommandExecutionException(String s, Exception e) {
        super(s, e);
    }
}
