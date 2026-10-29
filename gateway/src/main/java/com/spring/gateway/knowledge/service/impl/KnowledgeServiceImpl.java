package com.spring.gateway.knowledge.service.impl;

import com.spring.gateway.common.BizException;
import com.spring.gateway.knowledge.dto.KnowledgeDocumentContent;
import com.spring.gateway.knowledge.dto.KnowledgeDocumentSummary;
import com.spring.gateway.knowledge.service.KnowledgeService;
import com.spring.gateway.storage.infrastructure.StorageException;
import com.spring.gateway.storage.service.UserStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import static com.spring.gateway.storage.infrastructure.StoragePaths.checkedAttributes;

/**
 * 基于用户知识目录的文档查询。
 *
 * @author hanbing
 * @since 2026-10-29
 */
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {
    private static final List<DocumentDefinition> DOCUMENTS = List.of(
            new DocumentDefinition("declarative", "declarative.md"),
            new DocumentDefinition("procedural", "procedural.md"),
            new DocumentDefinition("conditional", "conditional.md")
    );

    private final UserStorageService userStorageService;

    @Override
    public List<KnowledgeDocumentSummary> list(long userId) {
        Path root = userStorageService.knowledgeRoot(userId);
        return DOCUMENTS.stream().map(document -> summarize(root, document)).toList();
    }

    @Override
    public KnowledgeDocumentContent open(long userId, String type) {
        DocumentDefinition document = DOCUMENTS.stream()
                .filter(candidate -> candidate.type().equals(type))
                .findFirst()
                .orElseThrow(() -> new BizException(404, "知识文档不存在"));
        Path path = userStorageService.knowledgeRoot(userId).resolve(document.fileName());
        if (checkedAttributes(path) == null) throw new BizException(404, "知识文档不存在");
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try {
                return new KnowledgeDocumentContent(Channels.newInputStream(channel), channel.size());
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        } catch (NoSuchFileException e) {
            throw new BizException(404, "知识文档不存在");
        } catch (IOException e) {
            throw new StorageException("知识文档读取失败", e);
        }
    }

    private static KnowledgeDocumentSummary summarize(Path root, DocumentDefinition document) {
        BasicFileAttributes attributes = checkedAttributes(root.resolve(document.fileName()));
        if (attributes == null) {
            return new KnowledgeDocumentSummary(document.type(), false, 0L, null);
        }
        if (!attributes.isRegularFile()) throw new StorageException("知识文档需要为普通文件");
        return new KnowledgeDocumentSummary(document.type(), true, attributes.size(),
                attributes.lastModifiedTime().toInstant().toString());
    }

    private record DocumentDefinition(String type, String fileName) {
    }
}
