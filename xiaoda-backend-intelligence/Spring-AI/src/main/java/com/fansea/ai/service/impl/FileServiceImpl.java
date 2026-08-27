package com.fansea.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fansea.ai.domain.Knowledge;
import com.fansea.ai.domain.KnowledgeFile;
import com.fansea.ai.mapper.FileMapper;
import com.fansea.ai.service.FileService;
import com.fansea.ai.service.KnowledgeFileService;
import com.fansea.ai.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import static com.fansea.ai.util.FileUtil.getFileTypeByExtension;

/**
 * @Projectname: Spring-AI
 * @Filename: FileServiceImpl
 * @Author: FANSEA
 * @Date:2025/4/26 16:28
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileServiceImpl extends ServiceImpl<FileMapper, com.fansea.ai.domain.File> implements FileService {
    
    @Value("${file.uploadPath}")
    private String path;

    private final KnowledgeFileService knowledgeFileService;
    @Lazy
    private final KnowledgeService knowledgeService;

    @Override
    public Long uploadDocument(MultipartFile file) {
        //保存file到本地
        String fileName = file.getOriginalFilename();
        String filepath = path + fileName;
        File file1 = new File(filepath);
        if(file1.exists()){
            throw new RuntimeException("文件已存在!");
        }else if (fileName == null|| fileName.isEmpty()) {
            throw new RuntimeException("文件名为空!");
        }
        try {
            file.transferTo(file1);
        } catch (Exception e) {
            e.printStackTrace();
        }
        String fileType = getFileTypeByExtension(fileName);
        com.fansea.ai.domain.File documentFile = new com.fansea.ai.domain.File(fileName,file.getSize(),fileType,filepath);
        save(documentFile);
        return documentFile.getId();
    }

    @Override
    public Long uploadToKnowledge(MultipartFile file,Long knowledgeId) {
        boolean exists = knowledgeService.exists(new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getId, knowledgeId));
        if (!exists) {
            throw new RuntimeException("知识库不存在!");
        }
        Long fileId = uploadDocument(file);
        knowledgeFileService.save(new KnowledgeFile(knowledgeId,fileId));
        return fileId;
    }
}
