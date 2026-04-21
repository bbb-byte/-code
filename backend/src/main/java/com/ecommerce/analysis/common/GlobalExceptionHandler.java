package com.ecommerce.analysis.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * Handles framework-level exceptions and converts them into user-facing API results.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String FILE_TOO_LARGE_MESSAGE = "上传文件过大，请压缩后重试，或调整后端上传大小限制。";

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("Upload rejected because the file exceeded the configured size limit: {}", e.getMessage());
        return Result.error(413, FILE_TOO_LARGE_MESSAGE);
    }

    @ExceptionHandler(MultipartException.class)
    public Result<Void> handleMultipartException(MultipartException e) {
        String message = e.getMessage();
        if (message != null && message.toLowerCase().contains("size")) {
            log.warn("Multipart request failed because the file exceeded the configured size limit: {}", message);
            return Result.error(413, FILE_TOO_LARGE_MESSAGE);
        }
        throw e;
    }
}
