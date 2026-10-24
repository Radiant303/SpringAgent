package com.spring.gateway.settings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 站长配置文件存储的请求体。endpoint/bucket/accessKeyId 留空表示保持不变；
 * accessKeySecret 只写不读，留空表示保持不变。
 *
 * @param type            存储类型（local / aliyun_oss）
 * @param endpoint        OSS endpoint
 * @param bucket          OSS bucket
 * @param accessKeyId     OSS AccessKey ID
 * @param accessKeySecret OSS AccessKey Secret
 * @param reconcileCron   存储对账 cron（六段式；空 = 恢复默认）
 */
public record AdminStorageSettingsRequest(
        @NotBlank(message = "存储类型不能为空") @Size(max = 32) String type,
        @Size(max = 256) String endpoint,
        @Size(max = 128) String bucket,
        @Size(max = 128) String accessKeyId,
        @Size(max = 128) String accessKeySecret,
        @Size(max = 64) String reconcileCron) {
}
