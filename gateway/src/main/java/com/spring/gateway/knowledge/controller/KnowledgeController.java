package com.spring.gateway.knowledge.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.Result;
import com.spring.gateway.knowledge.dto.KnowledgeDocumentContent;
import com.spring.gateway.knowledge.dto.KnowledgeDocumentSummary;
import com.spring.gateway.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前登录用户的知识库接口。
 *
 * @author hanbing
 * @since 2026-10-29
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {
    private final KnowledgeService knowledgeService;

    /**
     * 查询当前用户的知识文档元数据。
     *
     * @param userId 当前用户 ID
     * @return 知识文档元数据列表
     */
    @GetMapping
    public Result<List<KnowledgeDocumentSummary>> list(
            @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return Result.ok(knowledgeService.list(userId));
    }

    /**
     * 流式返回当前用户指定类型的完整知识文档。
     *
     * @param userId 当前用户 ID
     * @param type 文档类型
     * @return Markdown 文档内容
     */
    @GetMapping("/{type}")
    public ResponseEntity<Resource> content(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                            @PathVariable String type) {
        KnowledgeDocumentContent document = knowledgeService.open(userId, type);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .contentLength(document.sizeBytes())
                .body(new InputStreamResource(document.inputStream()));
    }
}
