package io.spaship.operator.type;

public record GeneralResponse<T>(T data, Status status) {
    public enum Status {
        OK, ERR, ACCEPTED,READY,IN_PROGRESS
    }
}
