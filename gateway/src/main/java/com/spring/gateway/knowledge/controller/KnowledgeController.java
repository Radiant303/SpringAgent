package com.spring.gateway.knowledge.controller;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.Result;
import com.spring.gateway.knowledge.dto.KnowledgeDocumentView;
import com.spring.gateway.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
     * 查询当前用户的知识文档。
     *
     * @param userId 当前用户 ID
     * @return 知识文档列表
     */
    @GetMapping
    public Result<List<KnowledgeDocumentView>> list(
            @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return Result.ok(knowledgeService.list(userId));
    }
}
