package com.spring.gateway.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

/**
 * 缓存凭证加密器。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@Component
@Lazy(false)
public class CacheSecretCipher {
    public static final String ENVIRONMENT_KEY = "SPRINGHARNESS_CACHE_MASTER_KEY";
    private static final String PREFIX = "enc:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    @Autowired
    public CacheSecretCipher(@Value("${app.data-root}") String dataRoot) {
        this(load(Path.of(dataRoot), System.getenv(ENVIRONMENT_KEY)).key.getEncoded());
    }

    CacheSecretCipher(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("缓存主密钥格式无效");
        }
        this.key = new SecretKeySpec(key.clone(), "AES");
    }

    public static CacheSecretCipher load(Path dataRoot, String environmentValue) {
        try {
            if (environmentValue != null) {
                return new CacheSecretCipher(decodeKey(environmentValue));
            }
            Path directory = dataRoot.toAbsolutePath().normalize().resolve(".secrets");
            Files.createDirectories(directory.getParent());
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(directory, privateAttribute(directory, true));
                } catch (FileAlreadyExistsException ignored) {
                }
            }
            requireType(directory, true);
            restrict(directory, true);
            Path target = directory.resolve("cache-master.key");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return readKey(target);
            }
            Path temporary = null;
            try {
                temporary = Files.createTempFile(directory, ".cache-master-", ".tmp", privateAttribute(directory, false));
                restrict(temporary, false);
                byte[] bytes = new byte[32];
                RANDOM.nextBytes(bytes);
                ByteBuffer encoded = ByteBuffer.wrap(Base64.getEncoder().encode(bytes));
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
                    while (encoded.hasRemaining()) {
                        channel.write(encoded);
                    }
                    channel.force(true);
                }
                requireType(directory, true);
                try {
                    Files.createLink(target, temporary);
                } catch (FileAlreadyExistsException ignored) {
                }
                return readKey(target);
            } finally {
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("缓存主密钥初始化失败");
        }
    }

    private static CacheSecretCipher readKey(Path path) throws IOException {
        requireType(path, false);
        restrict(path, false);
        byte[] bytes;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() < 44 || channel.size() > 45) {
                throw new IllegalArgumentException();
            }
            ByteBuffer buffer = ByteBuffer.allocate(46);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
            }
            bytes = Arrays.copyOf(buffer.array(), buffer.position());
        }
        if (bytes.length == 45 && bytes[44] == '\n') {
            bytes = Arrays.copyOf(bytes, 44);
        }
        return new CacheSecretCipher(decodeKey(StandardCharsets.US_ASCII.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()));
    }

    private static byte[] decodeKey(String encoded) {
        byte[] decoded = Base64.getDecoder().decode(encoded);
        if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
            throw new IllegalArgumentException();
        }
        return decoded;
    }

    private static void requireType(Path path, boolean directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            throw new IOException();
        }
    }

    private static FileAttribute<?> privateAttribute(Path path, boolean directory) throws IOException {
        Path existing = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? path : path.getParent();
        if (Files.getFileStore(existing).supportsFileAttributeView(PosixFileAttributeView.class)) {
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }
        if (Files.getFileStore(existing).supportsFileAttributeView(AclFileAttributeView.class)) {
            List<AclEntry> entries = privateAcl(path, directory);
            return new FileAttribute<List<AclEntry>>() {
                public String name() { return "acl:acl"; }
                public List<AclEntry> value() { return entries; }
            };
        }
        throw new IOException();
    }

    private static List<AclEntry> privateAcl(Path path, boolean directory) throws IOException {
        UserPrincipal principal = path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        }
        return List.of(entry.build());
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException();
            }
            view.setAcl(privateAcl(path, directory));
            if (!view.getAcl().equals(privateAcl(path, directory))) {
                throw new IOException();
            }
        }
    }

    public String encrypt(String plaintext, String cacheKey, String... path) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(cacheKey, path));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce).put(ciphertext).array());
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            throw new IllegalArgumentException("缓存凭证加密失败");
        }
    }

    public String decrypt(String encoded, String cacheKey, String... path) {
        try {
            if (!encoded.startsWith(PREFIX)) {
                throw new IllegalArgumentException();
            }
            String body = encoded.substring(PREFIX.length());
            byte[] bytes = Base64.getDecoder().decode(body);
            if (bytes.length < 28 || !Base64.getEncoder().encodeToString(bytes).equals(body)) {
                throw new IllegalArgumentException();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(aad(cacheKey, path));
            byte[] plaintext = cipher.doFinal(bytes, 12, bytes.length - 12);
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString();
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            throw new IllegalArgumentException("缓存凭证解密失败");
        }
    }

    private static byte[] aad(String cacheKey, String[] path) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeSegment(output, "springharness-cache:v1");
            writeSegment(output, cacheKey);
            for (String segment : path) {
                writeSegment(output, segment);
            }
        }
        return bytes.toByteArray();
    }

    private static void writeSegment(DataOutputStream output, String segment) throws IOException {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
