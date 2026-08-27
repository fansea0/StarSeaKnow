package com.fanseA.ai.controller;

import com.fangsa.ai.auth.RequireLogin;
import com.fangsa.ai.auth.RequireRole;
import com.fanseA.ai.domain.dto.AjaxResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * @Projectname: Spring-AI
 * @Filename: ToolController
 * @Author: FANSEA
 * @Date:2025/5/10 14:05
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/tool")
@RequireLogin
public class ToolController {

    @Value("${file.outputPath}")
    private String outputPath;

    @RequireRole("tenant_admin")
    @PostMapping(value = "/convmd")
    public AjaxResult createMarkdownFile(String fileName, @RequestBody String mdContent) {
        System.out.println(mdContent);
        // 生成唯一的文件名，避免文件名冲突
        if (fileName == null || fileName.isEmpty()) {
            fileName = UUID.randomUUID().toString() + ".md";
        }else {
            fileName = fileName + ".md";
        }
        Path filePath = Paths.get(outputPath, fileName); // 指定保存路径
        try {
            // 确保上传目录存在
            Files.createDirectories(filePath.getParent());

            // 将Markdown内容写入文件
            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                writer.write(mdContent);
            }

            // 返回成功响应，包含文件名
            return AjaxResult.success("md文件生成成功!",outputPath+fileName);
        } catch (IOException e) {
            // 处理IO异常
            return AjaxResult.error("md文件生成异常");
        }
    }
}
