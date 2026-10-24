package com.spring.gateway.storage.infrastructure;

import java.util.regex.Pattern;

/**
 * 存储 key 的统一约定：上传文件的 key 永远形如 works/{workId}/{sessionId}/images/{fileId}.{ext}。
 * 对账扫孤儿只认这个前缀形态，绝不触碰工作区里的其他文件（代理产物不属于上传文件）。
 *
 * @author hanbing
 * @since 2026-10-14
 */
public final class StorageKeys {

    /** 受管上传文件的 key 形态。 */
    public static final Pattern MANAGED = Pattern.compile("^works/[^/]+/[^/]+/images/[^/]+$");

    private StorageKeys() {
    }

    /**
     * 拼上传文件的存储 key
     *
     * @param workId    work ID
     * @param sessionId 会话 ID
     * @param fileId    文件 ID（雪花 ID，即文件名）
     * @param ext       扩展名（不含点）
     * @return 存储 key
     */
    public static String imageKey(String workId, String sessionId, String fileId, String ext) {
        return "works/" + workId + "/" + sessionId + "/images/" + fileId + "." + ext;
    }

    /**
     * key 是否属于受管上传文件
     *
     * @param key 存储 key
     * @return true = 上传文件
     */
    public static boolean isManaged(String key) {
        return MANAGED.matcher(key).matches();
    }
}
