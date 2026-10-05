package com.github.feng111627.idempotentwriteapijava.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带对外的错误码与 HTTP 状态，不泄露内部细节。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;

    public ApiException(ErrorCode code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ErrorCode getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
