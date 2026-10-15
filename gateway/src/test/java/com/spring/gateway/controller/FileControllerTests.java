package com.spring.gateway.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spring.gateway.common.JwtUtils;
import com.spring.gateway.common.SnowflakeIdGenerator;
import com.spring.gateway.entity.FileRecord;
import com.spring.gateway.entity.Session;
import com.spring.gateway.entity.User;
import com.spring.gateway.entity.Work;
import com.spring.gateway.mapper.FileRecordMapper;
import com.spring.gateway.mapper.SessionMapper;
import com.spring.gateway.mapper.UserMapper;
import com.spring.gateway.mapper.WorkMapper;
import com.spring.gateway.service.SystemSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

/**
 * 批量上传接口测试。
 *
 * @author hanbing
 * @since 2026-10-15
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.data-root=${java.io.tmpdir}/springharness-test-data")
class FileControllerTests {

    @Autowired private MockMvc mvc;
    @Autowired private JwtUtils jwt;
    @Autowired private SnowflakeIdGenerator ids;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkMapper workMapper;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private FileRecordMapper fileRecordMapper;
    @MockitoBean private SystemSettingService systemSettingService;
    @Value("${app.data-root}")
    private String dataRoot;

    private User user;
    private Session session;
    private String bearer;

    @BeforeEach
    void fixture() {
        when(systemSettingService.getStorageType()).thenReturn("local");
        when(systemSettingService.getWorkMaxBytes()).thenReturn(1L << 40);
        user = new User();
        user.setId(ids.nextId());
        user.setUsername("t_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("test-only");
        user.setQuotaBytes(1L << 40);
        userMapper.insert(user);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Work work = new Work();
        work.setId(UUID.randomUUID().toString());
        work.setUserId(user.getId());
        work.setName("t");
        work.setSizeBytes(0L);
        work.setIsDefault(false);
        work.setCreatedAt(now);
        work.setUpdatedAt(now);
        workMapper.insert(work);
        session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(user.getId());
        session.setWorkId(work.getId());
        session.setCurrentSegment(0);
        session.setStatus(Session.STATUS_ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);
        bearer = "Bearer " + jwt.createToken(user.getId(), user.getUsername());
    }

    @AfterEach
    void cleanup() throws Exception {
        Path root = Path.of(dataRoot, "works", session.getWorkId());
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private MockMultipartFile part(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "image/png", bytes);
    }

    @Test
    void batchRejectsMoreThanFiftyFiles() throws Exception {
        var request = multipart("/api/files/batch").header("Authorization", bearer)
                .param("sessionId", session.getId());
        for (int i = 0; i < 51; i++) {
            request.file(part(i + ".png", new byte[]{1}));
        }
        mvc.perform(request).andExpect(status().isBadRequest());
        assertEquals(0, fileRecordMapper.selectCount(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getSessionId, session.getId())));
    }

    @Test
    void batchRejectsMissingFilesPart() throws Exception {
        mvc.perform(multipart("/api/files/batch").header("Authorization", bearer)
                        .param("sessionId", session.getId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchHandlesEachFileIndependently() throws Exception {
        mvc.perform(multipart("/api/files/batch").header("Authorization", bearer)
                        .param("sessionId", session.getId())
                        .file(part("a.png", new byte[]{1, 2, 3}))
                        .file(part("empty.png", new byte[0]))
                        .file(part("b.png", new byte[]{4, 5})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploaded.length()").value(2))
                .andExpect(jsonPath("$.data.uploaded[0].index").value(0))
                .andExpect(jsonPath("$.data.uploaded[1].index").value(2))
                .andExpect(jsonPath("$.data.failed.length()").value(1))
                .andExpect(jsonPath("$.data.failed[0].index").value(1));
        assertEquals(2, fileRecordMapper.selectCount(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getSessionId, session.getId())));
        assertEquals(5, userMapper.selectById(user.getId()).getUsedBytes());
    }
}
