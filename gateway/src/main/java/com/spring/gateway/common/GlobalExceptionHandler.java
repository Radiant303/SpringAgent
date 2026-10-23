package com.spring.gateway.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器
 *
 * @author hanbing
 * @since 2026-09-26
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常，返回异常携带的状态码与描述
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> biz(BizException e) {
        return ResponseEntity.status(e.getStatus()).body(Result.error(e.getStatus(), e.getMessage()));
    }

    /**
     * 处理刷新令牌失效。
     */
    @ExceptionHandler(com.spring.gateway.service.AuthSessionService.TokenException.class)
    public ResponseEntity<Result<Void>> token(com.spring.gateway.service.AuthSessionService.TokenException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(com.spring.gateway.service.AuthSessionService.AUTH_ERROR_HEADER, e.getErrorCode())
                .body(Result.error(HttpStatus.UNAUTHORIZED.value(), e.getMessage()));
    }

    /**
     * 处理参数校验失败异常，返回第一条字段校验错误信息
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("参数错误");
        return ResponseEntity.badRequest().body(Result.error(HttpStatus.BAD_REQUEST.value(), message));
    }

    /**
     * 处理请求体不可读异常，返回 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Result.error(HttpStatus.BAD_REQUEST.value(), "请求体格式错误"));
    }

    /**
     * 处理请求参数或分片缺失异常，返回 400
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Result<Void>> missingParam(Exception e) {
        String name = e instanceof MissingServletRequestParameterException param ? param.getParameterName()
                : e instanceof MissingServletRequestPartException part ? part.getRequestPartName() : "";
        return ResponseEntity.badRequest()
                .body(Result.error(HttpStatus.BAD_REQUEST.value(), "缺少必要参数: " + name));
    }

    /**
     * 处理路径/查询参数类型不匹配（如 Long 参数收到非数字），返回 400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(Result.error(HttpStatus.BAD_REQUEST.value(), "参数类型错误: " + e.getName()));
    }

    /**
     * 处理请求方法不支持异常，返回 405
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.error(HttpStatus.METHOD_NOT_ALLOWED.value(), "请求方法不支持"));
    }

    /**
     * 处理 URL 无匹配处理器的异常，返回 404
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(HttpStatus.NOT_FOUND.value(), "接口不存在"));
    }

    /**
     * 存储后端失败，统一返回 502
     */
    @ExceptionHandler(com.spring.gateway.service.storage.StorageException.class)
    public ResponseEntity<Result<Void>> storage(com.spring.gateway.service.storage.StorageException e) {
        log.warn("存储操作失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Result.error(HttpStatus.BAD_GATEWAY.value(), e.getMessage()));
    }

    /**
     * 上传超 multipart 限制，统一返回 413
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> tooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        long maxMb = com.spring.gateway.service.FileStorageService.MAX_FILE_BYTES / 1024 / 1024;
        String message = "文件数量或大小超限：单文件不能超过 " + maxMb + " MB，一次最多上传 "
                + com.spring.gateway.controller.FileController.MAX_BATCH_FILES + " 个";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.error(HttpStatus.PAYLOAD_TOO_LARGE.value(), message));
    }

    /**
     * 处理未捕获异常，统一返回 500；错误细节只记录日志，不写入响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unknown(Exception e) {
        log.error("未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务器内部错误"));
    }
}
