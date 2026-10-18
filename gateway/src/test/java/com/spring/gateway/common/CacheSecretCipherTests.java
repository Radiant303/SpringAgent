package com.spring.gateway.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 缓存凭证加密与主密钥初始化测试。
 *
 * @author hanbing
 * @since 2026-10-17
 */
class CacheSecretCipherTests {
    @TempDir
    Path root;
    private final String fixtureKey = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsUtf8AndEmptyStringsWithRandomNonces() {
        CacheSecretCipher cipher = new CacheSecretCipher(new byte[32]);
        for (String plaintext : List.of("", "测试凭证", "fixtureSecret")) {
            String first = cipher.encrypt(plaintext, "sysconf:settings", "mail.auth_code");
            assertTrue(first.startsWith("enc:v1:"));
            assertNotEquals(first, cipher.encrypt(plaintext, "sysconf:settings", "mail.auth_code"));
            assertEquals(plaintext, cipher.decrypt(first, "sysconf:settings", "mail.auth_code"));
            assertTrue(Base64.getDecoder().decode(first.substring(7)).length >= 28);
        }
    }

    @Test
    void decryptsFixedPythonAesGcmVectors() {
        CacheSecretCipher cipher = new CacheSecretCipher(new byte[32]);
        assertEquals("测试凭证", cipher.decrypt(
                "enc:v1:AAECAwQFBgcICQoLbn64uq9oan5vkP1kWqabxiNTlroD8kNPPQ7Aaw==",
                "sysconf:settings", "mail.auth_code"));
        assertEquals("", cipher.decrypt("enc:v1:AAECAwQFBgcICQoLU29ObJi7HevPwawCqf3hcg==",
                "sysconf:models", "providers", "厂商.a:b", "api_key"));
    }

    @Test
    void authenticatesEveryAadSegmentAndKey() {
        CacheSecretCipher cipher = new CacheSecretCipher(new byte[32]);
        String encrypted = cipher.encrypt("fixtureSecret", "catalog", "providers", "厂商.a:b", "api_key");
        assertEquals("fixtureSecret", cipher.decrypt(encrypted, "catalog", "providers", "厂商.a:b", "api_key"));
        assertThrows(IllegalArgumentException.class,
                () -> cipher.decrypt(encrypted, "other", "providers", "厂商.a:b", "api_key"));
        assertThrows(IllegalArgumentException.class,
                () -> cipher.decrypt(encrypted, "catalog", "providers.厂商.a:b", "api_key"));
        assertThrows(IllegalArgumentException.class,
                () -> cipher.decrypt(encrypted, "catalog", "providers", "厂商.a:b", "other"));
        byte[] other = new byte[32];
        other[0] = 1;
        assertThrows(IllegalArgumentException.class,
                () -> new CacheSecretCipher(other).decrypt(encrypted, "catalog", "providers", "厂商.a:b", "api_key"));
    }

    @Test
    void rejectsTamperingTruncationAndNonCanonicalEncoding() {
        CacheSecretCipher cipher = new CacheSecretCipher(new byte[32]);
        String encrypted = cipher.encrypt("fixtureSecret", "cache", "field");
        byte[] bytes = Base64.getDecoder().decode(encrypted.substring(7));
        bytes[12] ^= 1;
        List<String> invalid = List.of("fixtureSecret", "enc:v2:" + encrypted.substring(7), "enc:v1:",
                "enc:v1:" + Base64.getEncoder().encodeToString(bytes), encrypted + "\n",
                encrypted.substring(0, encrypted.length() - 4));
        for (String value : invalid) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> cipher.decrypt(value, "cache", "field"));
            assertEquals("缓存凭证解密失败", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test
    void generatesOnceAndReusesPrivateFileWithoutNewline() throws Exception {
        CacheSecretCipher first = CacheSecretCipher.load(root, null);
        Path directory = root.resolve(".secrets");
        Path key = directory.resolve("cache-master.key");
        byte[] original = Files.readAllBytes(key);
        assertEquals(44, original.length);
        assertEquals(32, Base64.getDecoder().decode(original).length);
        CacheSecretCipher second = CacheSecretCipher.load(root, null);
        assertArrayEquals(original, Files.readAllBytes(key));
        assertEquals("fixtureSecret", second.decrypt(first.encrypt("fixtureSecret", "cache", "field"), "cache", "field"));
        if (Files.getFileAttributeView(key, PosixFileAttributeView.class) != null) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(key));
        } else {
            var current = key.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            for (Path entry : List.of(directory, key)) {
                var acl = Files.getFileAttributeView(entry, AclFileAttributeView.class).getAcl();
                assertEquals(1, acl.size());
                assertEquals(current, acl.getFirst().principal());
                assertEquals(AclEntryType.ALLOW, acl.getFirst().type());
            }
        }
        try (var files = Files.list(directory)) {
            assertEquals(List.of(key), files.toList());
        }
    }

    @Test
    void environmentOverrideDoesNotReadOrWriteFiles() throws Exception {
        Path secrets = Files.createDirectories(root.resolve(".secrets"));
        Path file = Files.writeString(secrets.resolve("cache-master.key"), "broken");
        CacheSecretCipher cipher = CacheSecretCipher.load(root, fixtureKey);
        assertEquals("broken", Files.readString(file));
        assertEquals("", cipher.decrypt(cipher.encrypt("", "cache"), "cache"));
        Path unused = root.resolve("does-not-exist");
        CacheSecretCipher.load(unused, fixtureKey);
        assertFalse(Files.exists(unused));
    }

    @Test
    void invalidEnvironmentNeverFallsBack() throws Exception {
        CacheSecretCipher.load(root, null);
        byte[] original = Files.readAllBytes(root.resolve(".secrets/cache-master.key"));
        for (String invalid : List.of("", " ", "bad", fixtureKey.stripTrailing() + "\n",
                fixtureKey.substring(0, fixtureKey.length() - 1), Base64.getEncoder().encodeToString(new byte[31]),
                fixtureKey.substring(0, 42) + "B=")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> CacheSecretCipher.load(root, invalid));
            assertEquals("缓存主密钥初始化失败", failure.getMessage());
            assertNull(failure.getCause());
        }
        assertArrayEquals(original, Files.readAllBytes(root.resolve(".secrets/cache-master.key")));
    }

    @Test
    void acceptsOneLfButRejectsDamagedOrOversizedExistingFiles() throws Exception {
        Path key = Files.createDirectories(root.resolve(".secrets")).resolve("cache-master.key");
        Files.writeString(key, fixtureKey + "\n");
        CacheSecretCipher.load(root, null);
        for (String invalid : List.of("", "broken", fixtureKey + "\r\n", fixtureKey + "\n\n", "x".repeat(10000))) {
            Files.writeString(key, invalid);
            assertThrows(IllegalStateException.class, () -> CacheSecretCipher.load(root, null));
            assertEquals(invalid, Files.readString(key));
        }
    }

    @Test
    void concurrentFirstStartupPublishesOneWinnerAndCleansTemporaryFiles() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<CacheSecretCipher>> tasks = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                tasks.add(() -> CacheSecretCipher.load(root, null));
            }
            var results = executor.invokeAll(tasks);
            CacheSecretCipher first = results.getFirst().get();
            String encrypted = first.encrypt("fixtureSecret", "cache", "field");
            for (var result : results) {
                assertEquals("fixtureSecret", result.get().decrypt(encrypted, "cache", "field"));
            }
        }
        try (var files = Files.list(root.resolve(".secrets"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void rejectsSecretDirectorySymlink() throws Exception {
        Path external = Files.createDirectories(root.resolve("external"));
        Path link = root.resolve(".secrets");
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "当前账号不支持创建符号链接");
        }
        assertThrows(IllegalStateException.class, () -> CacheSecretCipher.load(root, null));
        assertFalse(Files.exists(external.resolve("cache-master.key")));
    }

    @Test
    void rejectsSecretDirectoryJunctionOnWindows() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path external = Files.createDirectories(root.resolve("external"));
        Path link = root.resolve(".secrets");
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), external.toString())
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor());
        try {
            assertThrows(IllegalStateException.class, () -> CacheSecretCipher.load(root, null));
            assertFalse(Files.exists(external.resolve("cache-master.key")));
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void rejectsKeySymlinkAndDirectory() throws Exception {
        Path external = Files.writeString(root.resolve("external.key"), fixtureKey);
        Path key = Files.createDirectories(root.resolve(".secrets")).resolve("cache-master.key");
        try {
            Files.createSymbolicLink(key, external);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "当前账号不支持创建符号链接");
        }
        assertThrows(IllegalStateException.class, () -> CacheSecretCipher.load(root, null));
        assertTrue(Files.isSymbolicLink(key));
        Files.delete(key);
        Files.createDirectory(key);
        assertThrows(IllegalStateException.class, () -> CacheSecretCipher.load(root, null));
        assertTrue(Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS));
    }
}
