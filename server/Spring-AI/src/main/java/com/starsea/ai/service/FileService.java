package com.fansea.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fansea.ai.domain.File;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

/**
 * @Projectname: Spring-AI
 * @Filename: FileService
 * @Author: FANSEA
 * @Date:2025/4/26 16:28
 */
public interface FileService extends IService<File> {
    Long uploadDocument(MultipartFile file);
    Long uploadToKnowledge(MultipartFile file,Long knowledgeId);
    List<File> listEnabledByKnowledgeIds(Long tenantId, Set<Long> knowledgeIds);
}
