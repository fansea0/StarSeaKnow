package com.fansea.ai.controller;

import com.fansea.ai.auth.RequireLogin;
import com.fansea.ai.auth.RequireRole;
import com.fansea.ai.domain.File;
import com.fansea.ai.domain.dto.AjaxResult;
import com.fansea.ai.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * @Projectname: Spring-AI
 * @Filename: FileController
 * @Author: FANSEA
 * @Date:2025/5/3 13:21
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/file")
@RequireLogin
public class FileController {

    private final FileService fileService;
    private final VectorStore vectorStore;

    @RequireRole("tenant_admin")
    @PostMapping("/upload")
    public AjaxResult uploadFile(MultipartFile file){
        return AjaxResult.success(fileService.uploadDocument(file));
    }

    @RequireRole("tenant_admin")
    @PostMapping("/uploadToKnow/{knowledgeId}")
    public AjaxResult uploadFileToKnowledge(MultipartFile file, @PathVariable Long knowledgeId){
        return AjaxResult.success(fileService.uploadToKnowledge(file, knowledgeId));
    }

    @RequireRole("tenant_admin")
    @DeleteMapping("/delete/{fileId}")
    public AjaxResult deleteFile(@PathVariable Long fileId){
        File file = fileService.getById(fileId);
        if(file == null){
            return AjaxResult.error("文件不存在");
        }
        String path = file.getPath();
        boolean delete = new java.io.File(path).delete();
        if(delete){
            fileService.removeById(fileId);
            // 防御性:删除向量时也限定tenantId,避免误删其他租户的向量数据
            com.fansea.ai.auth.AuthContext ctx = com.fansea.ai.auth.AuthContext.current();
            String tenantExpr = (ctx == null || ctx.getTenantId() == null)
                    ? "tenantId == -1"
                    : "tenantId == " + ctx.getTenantId();
            vectorStore.delete("(fileId == " + fileId + ") && " + tenantExpr);
            return AjaxResult.success("删除成功");
        }
        return AjaxResult.error("删除失败");
    }

    @RequireRole("tenant_admin")
    @PutMapping("/updateStatus/{fileId}")
    public AjaxResult updateFile(Integer status, @PathVariable Long fileId){
        File file = fileService.getById(fileId);
        file.setStatus(status);
        fileService.updateById(file);
        return AjaxResult.success();
    }


}
