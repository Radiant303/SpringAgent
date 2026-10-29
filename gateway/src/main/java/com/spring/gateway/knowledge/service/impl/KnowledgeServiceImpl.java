package com.spring.gateway.knowledge.service.impl;

import com.spring.gateway.knowledge.dto.KnowledgeDocumentView;
import com.spring.gateway.knowledge.service.KnowledgeService;
import com.spring.gateway.storage.infrastructure.StorageException;
import com.spring.gateway.storage.service.UserStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

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
    public List<KnowledgeDocumentView> list(long userId) {
        userStorageService.knowledgeBytes(userId);
        Path root = userStorageService.knowledgeRoot(userId);
        return DOCUMENTS.stream().map(document -> read(root, document)).toList();
    }

    private static KnowledgeDocumentView read(Path root, DocumentDefinition document) {
        try {
            String content = Files.readString(root.resolve(document.fileName()), StandardCharsets.UTF_8);
            return new KnowledgeDocumentView(document.type(), content);
        } catch (NoSuchFileException e) {
            return new KnowledgeDocumentView(document.type(), "");
        } catch (IOException e) {
            throw new StorageException("知识文档读取失败", e);
        }
    }

    private record DocumentDefinition(String type, String fileName) {
    }
}
