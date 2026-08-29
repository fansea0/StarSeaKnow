package com.fansea.ai.controller;

import com.fansea.ai.auth.RequireLogin;
import com.fansea.ai.auth.RequireRole;
import com.fansea.ai.domain.dto.AjaxResult;
import com.fansea.ai.service.FileService;
import com.fansea.ai.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
@RestController
@RequestMapping("/rag")
@RequireLogin
public class RagController {

    private final FileService fileService;
    private final RagService ragService;

    /*@GetMapping("/file")
    public AjaxResult handle(MultipartFile file, String knowledgeId){
        // TODO: 文件分块 ---> 向量化处理 ---> 存入pgvector
        String filePath = fileService.uploadDocument(file);
        ragService.vectorize(filePath,knowledgeId);
        return AjaxResult.success();
    }*/

    @RequireRole("tenant_admin")
    @GetMapping("/file")
    public AjaxResult handle(MultipartFile file, String knowledgeId){
        // TODO: 文件分块 ---> 向量化处理 ---> 存入pgvector
        Long l = fileService.uploadDocument(file);
//        ragService.vectorize(filePath,knowledgeId);
        return AjaxResult.success();
    }
}
