package com.starsea.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.vo.FileVo;
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
    Long uploadToKnowledge(MultipartFile file,Long knowledgeId);
    List<FileVo> listByKnowledgeId(Long knowledgeId);
    List<File> listEnabledByKnowledgeIds(Long tenantId, Set<Long> knowledgeIds);
}
