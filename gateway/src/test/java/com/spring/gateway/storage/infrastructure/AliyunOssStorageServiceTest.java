package com.spring.gateway.storage.infrastructure;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.spring.gateway.settings.service.SystemSettingService;
import com.spring.gateway.settings.service.SystemSettingService.OssConfig;
import com.spring.gateway.storage.service.ObjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 阿里云 OSS 客户端生命周期测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class AliyunOssStorageServiceTest {

    private static final OssConfig CONFIG = new OssConfig(
            "https://oss.example.com", "bucket", "test-id", "test-secret");
    private static final OssConfig UPDATED = new OssConfig(
            "https://oss.example.com", "next-bucket", "next-id", "next-secret");
    private static final String KEY = "works/work/session/images/image.png";

    private SystemSettingService settings;
    private OSS first;
    private OSS second;
    private AliyunOssStorageService service;

    @BeforeEach
    void setUp() {
        settings = mock(SystemSettingService.class);
        first = mock(OSS.class);
        second = mock(OSS.class);
        service = spy(new AliyunOssStorageService(settings));
        when(settings.getOssConfig()).thenReturn(CONFIG);
        doReturn(first).when(service).buildClient(CONFIG);
        doReturn(second).when(service).buildClient(UPDATED);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void reusesEqualCompleteConfigAndPreservesUploadMetadata() {
        OssConfig equalConfig = new OssConfig(CONFIG.endpoint(), CONFIG.bucket(),
                CONFIG.accessKeyId(), CONFIG.accessKeySecret());
        when(settings.getOssConfig()).thenReturn(CONFIG, equalConfig);
        InputStream content = new ByteArrayInputStream(new byte[]{1, 2});

        assertEquals(KEY, service.upload(content, 2, KEY, "image/png"));
        service.delete(KEY);

        ArgumentCaptor<ObjectMetadata> metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(first).putObject(eq(CONFIG.bucket()), eq(KEY), eq(content), metadata.capture());
        assertEquals(2, metadata.getValue().getContentLength());
        assertEquals("image/png", metadata.getValue().getContentType());
        verify(first).deleteObject(CONFIG.bucket(), KEY);
        verify(settings, times(2)).getOssConfig();
        verify(service, times(1)).buildClient(any(OssConfig.class));
        verify(first, never()).shutdown();
        service.shutdown();
        service.shutdown();
        verify(first, times(1)).shutdown();
    }

    @Test
    void directUrlUsesOneSnapshotAndPreservesExpiration() throws Exception {
        URL url = new URL("https://bucket.example.com/image?signature=test");
        when(first.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(url);
        long before = System.currentTimeMillis();

        assertEquals(url.toString(), service.directUrl(KEY).orElseThrow());

        long after = System.currentTimeMillis();
        ArgumentCaptor<GeneratePresignedUrlRequest> request =
                ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(first).generatePresignedUrl(request.capture());
        assertEquals(CONFIG.bucket(), request.getValue().getBucketName());
        assertEquals(KEY, request.getValue().getKey());
        assertTrue(request.getValue().getExpiration().getTime() >= before + 3_600_000L);
        assertTrue(request.getValue().getExpiration().getTime() <= after + 3_600_000L);
        verify(settings, times(1)).getOssConfig();
    }

    @Test
    void paginatedListingUsesOneSnapshotAndFiltersManagedKeys() {
        Instant modified = Instant.parse("2026-10-17T01:00:00Z");
        ObjectListing pageOne = mock(ObjectListing.class);
        ObjectListing pageTwo = mock(ObjectListing.class);
        OSSObjectSummary managed = mock(OSSObjectSummary.class);
        OSSObjectSummary unmanaged = mock(OSSObjectSummary.class);
        when(managed.getKey()).thenReturn(KEY);
        when(managed.getLastModified()).thenReturn(Date.from(modified));
        when(unmanaged.getKey()).thenReturn("other/image.png");
        when(pageOne.getObjectSummaries()).thenReturn(List.of(managed, unmanaged));
        when(pageOne.isTruncated()).thenReturn(true);
        when(pageOne.getNextMarker()).thenReturn("next-page");
        when(pageTwo.getObjectSummaries()).thenReturn(List.of());
        when(first.listObjects(any(ListObjectsRequest.class))).thenReturn(pageOne, pageTwo);

        assertEquals(List.of(new ObjectStorageService.StoredObject(KEY, modified)),
                service.listObjects());

        ArgumentCaptor<ListObjectsRequest> requests = ArgumentCaptor.forClass(ListObjectsRequest.class);
        verify(first, times(2)).listObjects(requests.capture());
        for (ListObjectsRequest request : requests.getAllValues()) {
            assertEquals(CONFIG.bucket(), request.getBucketName());
            assertEquals("works/", request.getPrefix());
            assertEquals(1000, request.getMaxKeys());
        }
        assertNull(requests.getAllValues().get(0).getMarker());
        assertEquals("next-page", requests.getAllValues().get(1).getMarker());
        verify(settings, times(1)).getOssConfig();
        verify(service, times(1)).buildClient(CONFIG);
    }

    @Test
    void everyConfigFieldParticipatesInClientReuse() {
        List<OssConfig> changes = List.of(
                new OssConfig("https://new.example.com", CONFIG.bucket(),
                        CONFIG.accessKeyId(), CONFIG.accessKeySecret()),
                new OssConfig(CONFIG.endpoint(), "new-bucket",
                        CONFIG.accessKeyId(), CONFIG.accessKeySecret()),
                new OssConfig(CONFIG.endpoint(), CONFIG.bucket(),
                        "new-id", CONFIG.accessKeySecret()),
                new OssConfig(CONFIG.endpoint(), CONFIG.bucket(),
                        CONFIG.accessKeyId(), "new-secret"));
        for (OssConfig changed : changes) {
            SystemSettingService localSettings = mock(SystemSettingService.class);
            OSS oldClient = mock(OSS.class);
            OSS newClient = mock(OSS.class);
            AliyunOssStorageService localService = spy(new AliyunOssStorageService(localSettings));
            when(localSettings.getOssConfig()).thenReturn(CONFIG, changed);
            doReturn(oldClient).when(localService).buildClient(CONFIG);
            doReturn(newClient).when(localService).buildClient(changed);
            try {
                localService.delete(KEY);
                localService.delete(KEY);
                verify(oldClient).deleteObject(CONFIG.bucket(), KEY);
                verify(newClient).deleteObject(changed.bucket(), KEY);
                verify(oldClient, times(1)).shutdown();
                verify(newClient, never()).shutdown();
                verify(localSettings, times(2)).getOssConfig();
            } finally {
                localService.shutdown();
            }
            verify(oldClient, times(1)).shutdown();
            verify(newClient, times(1)).shutdown();
        }
    }

    @Test
    void rotationWaitsForLastOldRequestWithoutBlockingNewRequests() throws Exception {
        AtomicReference<OssConfig> config = new AtomicReference<>(CONFIG);
        when(settings.getOssConfig()).thenAnswer(invocation -> config.get());
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch finishOne = new CountDownLatch(1);
        CountDownLatch finishTwo = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            CountDownLatch finish = KEY.equals(invocation.getArgument(1)) ? finishOne : finishTwo;
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            return null;
        }).when(first).putObject(eq(CONFIG.bucket()), any(String.class),
                any(InputStream.class), any(ObjectMetadata.class));

        try (var executor = Executors.newFixedThreadPool(3)) {
            var uploadOne = executor.submit(() -> service.upload(
                    new ByteArrayInputStream(new byte[0]), 0, KEY, null));
            var uploadTwo = executor.submit(() -> service.upload(
                    new ByteArrayInputStream(new byte[0]), 0, "second-key", null));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                config.set(UPDATED);
                executor.submit(() -> service.delete(KEY)).get(5, TimeUnit.SECONDS);
                verify(second).deleteObject(UPDATED.bucket(), KEY);
                verify(first, never()).shutdown();

                finishOne.countDown();
                assertEquals(KEY, uploadOne.get(5, TimeUnit.SECONDS));
                verify(first, never()).shutdown();
                finishTwo.countDown();
                assertEquals("second-key", uploadTwo.get(5, TimeUnit.SECONDS));
                verify(first, times(1)).shutdown();
                verify(settings, times(3)).getOssConfig();
                verify(service, times(1)).buildClient(CONFIG);
                verify(service, times(1)).buildClient(UPDATED);
            } finally {
                finishOne.countDown();
                finishTwo.countDown();
            }
        }
        service.shutdown();
        verify(first, times(1)).shutdown();
        verify(second, times(1)).shutdown();
    }

    @Test
    void uploadFailureReleasesRetiredClient() {
        assertFailureReleasesRetiredClient(() -> service.upload(
                new ByteArrayInputStream(new byte[0]), 0, KEY, null));
    }

    @Test
    void deleteFailureReleasesRetiredClient() {
        assertFailureReleasesRetiredClient(() -> service.delete(KEY));
    }

    @Test
    void signingFailureReleasesRetiredClient() {
        assertFailureReleasesRetiredClient(() -> service.directUrl(KEY));
    }

    @Test
    void listingFailureReleasesRetiredClient() {
        assertFailureReleasesRetiredClient(() -> service.listObjects());
    }

    private void assertFailureReleasesRetiredClient(Runnable operation) {
        AtomicReference<OssConfig> config = new AtomicReference<>(CONFIG);
        when(settings.getOssConfig()).thenAnswer(invocation -> config.get());
        Answer<Object> fail = invocation -> {
            config.set(UPDATED);
            service.delete("rotate");
            verify(first, never()).shutdown();
            throw new ClientException(CONFIG.accessKeySecret());
        };
        doAnswer(fail).when(first).putObject(any(String.class), any(String.class),
                any(InputStream.class), any(ObjectMetadata.class));
        doAnswer(fail).when(first).deleteObject(any(String.class), any(String.class));
        doAnswer(fail).when(first).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));
        doAnswer(fail).when(first).listObjects(any(ListObjectsRequest.class));

        StorageException error = assertThrows(StorageException.class, operation::run);

        assertFalse(error.getMessage().contains(CONFIG.accessKeySecret()));
        assertNull(error.getCause());
        verify(first, times(1)).shutdown();
        verify(second).deleteObject(UPDATED.bucket(), "rotate");
        verify(settings, times(2)).getOssConfig();
        service.shutdown();
        verify(first, times(1)).shutdown();
        verify(second, times(1)).shutdown();
    }

    @Test
    void incompleteConfigFailsWritesAndSigningButKeepsListingEmpty() {
        for (OssConfig incomplete : List.of(
                new OssConfig("", CONFIG.bucket(), CONFIG.accessKeyId(), CONFIG.accessKeySecret()),
                new OssConfig(CONFIG.endpoint(), "", CONFIG.accessKeyId(), CONFIG.accessKeySecret()),
                new OssConfig(CONFIG.endpoint(), CONFIG.bucket(), "", CONFIG.accessKeySecret()),
                new OssConfig(CONFIG.endpoint(), CONFIG.bucket(), CONFIG.accessKeyId(), ""))) {
            when(settings.getOssConfig()).thenReturn(incomplete);
            assertTrue(assertThrows(StorageException.class, () -> service.upload(
                    new ByteArrayInputStream(new byte[0]), 0, KEY, null))
                    .getMessage().contains("未配置完整"));
            assertTrue(assertThrows(StorageException.class, () -> service.delete(KEY))
                    .getMessage().contains("未配置完整"));
            assertTrue(assertThrows(StorageException.class, () -> service.directUrl(KEY))
                    .getMessage().contains("未配置完整"));
            assertEquals(List.of(), service.listObjects());
            assertFalse(service.configured());
        }
        verify(settings, times(20)).getOssConfig();
        verify(service, never()).buildClient(any(OssConfig.class));
        verifyNoInteractions(first, second);
    }

    @Test
    void configuredReadsOneSnapshotWithoutBuildingClient() {
        assertTrue(service.configured());
        verify(settings, times(1)).getOssConfig();
        verify(service, never()).buildClient(any(OssConfig.class));
        verifyNoInteractions(first, second);
    }

    @Test
    void failedClientBuildKeepsPreviousClientAvailableWithoutLeakingSecrets() {
        when(settings.getOssConfig()).thenReturn(CONFIG, UPDATED, CONFIG);
        doThrow(new ClientException(UPDATED.accessKeySecret())).when(service).buildClient(UPDATED);
        service.delete(KEY);

        StorageException error = assertThrows(StorageException.class, () -> service.delete(KEY));

        assertEquals("OSS 客户端创建失败", error.getMessage());
        assertNull(error.getCause());
        verify(first, never()).shutdown();
        service.delete(KEY);
        verify(first, times(2)).deleteObject(CONFIG.bucket(), KEY);
        verify(service, times(1)).buildClient(CONFIG);
        verify(settings, times(3)).getOssConfig();
        service.shutdown();
        verify(first, times(1)).shutdown();
    }

    @Test
    void failedOldClientShutdownDoesNotPreventNewConfigRequests() {
        when(settings.getOssConfig()).thenReturn(CONFIG, UPDATED, UPDATED);
        doThrow(new IllegalStateException(CONFIG.accessKeySecret(),
                new IllegalStateException(CONFIG.accessKeyId()))).when(first).shutdown();
        service.delete("old-key");

        service.delete("new-key");
        service.delete("reused-key");

        verify(first).deleteObject(CONFIG.bucket(), "old-key");
        verify(second).deleteObject(UPDATED.bucket(), "new-key");
        verify(second).deleteObject(UPDATED.bucket(), "reused-key");
        verify(service, times(1)).buildClient(CONFIG);
        verify(service, times(1)).buildClient(UPDATED);
        verify(settings, times(3)).getOssConfig();
        verify(first, times(1)).shutdown();
        doThrow(new IllegalStateException(UPDATED.accessKeySecret())).when(second).shutdown();
        service.shutdown();
        service.shutdown();
        verify(first, times(1)).shutdown();
        verify(second, times(1)).shutdown();
    }

    @Test
    void failedRetiredClientShutdownDoesNotOverrideSuccessfulUpload() {
        AtomicReference<OssConfig> config = new AtomicReference<>(CONFIG);
        when(settings.getOssConfig()).thenAnswer(invocation -> config.get());
        doThrow(new IllegalStateException(CONFIG.accessKeySecret(),
                new IllegalStateException(CONFIG.accessKeyId()))).when(first).shutdown();
        doAnswer(invocation -> {
            config.set(UPDATED);
            service.delete("rotate");
            verify(first, never()).shutdown();
            return null;
        }).when(first).putObject(eq(CONFIG.bucket()), eq(KEY),
                any(InputStream.class), any(ObjectMetadata.class));

        assertEquals(KEY, service.upload(new ByteArrayInputStream(new byte[0]), 0, KEY, null));
        service.delete("reused-key");

        verify(second).deleteObject(UPDATED.bucket(), "rotate");
        verify(second).deleteObject(UPDATED.bucket(), "reused-key");
        verify(service, times(1)).buildClient(CONFIG);
        verify(service, times(1)).buildClient(UPDATED);
        verify(settings, times(3)).getOssConfig();
        verify(first, times(1)).shutdown();
        service.shutdown();
        service.shutdown();
        verify(first, times(1)).shutdown();
        verify(second, times(1)).shutdown();
    }

    @Test
    void stopClosesIdleCurrentAndDefersActiveRetiredClient() throws Exception {
        AtomicReference<OssConfig> config = new AtomicReference<>(CONFIG);
        when(settings.getOssConfig()).thenAnswer(invocation -> config.get());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            return null;
        }).when(first).deleteObject(CONFIG.bucket(), KEY);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> service.delete(KEY));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                config.set(UPDATED);
                service.delete("new-key");
                service.shutdown();
                service.shutdown();
                verify(second, times(1)).shutdown();
                verify(first, never()).shutdown();
                assertTrue(assertThrows(StorageException.class, () -> service.delete("after-stop"))
                        .getMessage().contains("已关闭"));
                verify(second, never()).deleteObject(UPDATED.bucket(), "after-stop");
                finish.countDown();
                request.get(5, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }
        service.shutdown();
        verify(first, times(1)).shutdown();
        verify(second, times(1)).shutdown();
    }

    @Test
    void stopDefersActiveCurrentClientUntilRequestFinishes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            return null;
        }).when(first).deleteObject(CONFIG.bucket(), KEY);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> service.delete(KEY));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                service.shutdown();
                verify(first, never()).shutdown();
                finish.countDown();
                request.get(5, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }
        service.shutdown();
        verify(first, times(1)).shutdown();
    }
}
