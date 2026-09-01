package com.starsea.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.domain.AgentKnowledge;
import com.starsea.ai.domain.Knowledge;
import com.starsea.ai.domain.KnowledgeFile;
import com.starsea.ai.domain.dto.AjaxResult;
import com.starsea.ai.domain.vo.KnowledgeVo;
import com.starsea.ai.service.AgentKnowledgeService;
import com.starsea.ai.service.FileService;
import com.starsea.ai.service.KnowledgeFileService;
import com.starsea.ai.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Projectname: Spring-AI
 * @Filename: KnowledgeController
 * @Author: FANSEA
 * @Date:2025/5/3 13:50
 */
@RequiredArgsConstructor
@RestController
@Slf4j
@RequestMapping("/knowledge")
@RequireLogin
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeFileService knowledgeFileService;
    private final AgentKnowledgeService agentKnowledgeService;
    private final FileService fileService;

    @GetMapping("/file/list")
    public AjaxResult fileList(Long knowledgeId) {
        return AjaxResult.success(fileService.listByKnowledgeId(knowledgeId));
    }

    @GetMapping("/file/count")
    public AjaxResult knowledgeFileCount(Long knowledgeId){
        long count = knowledgeFileService.count(new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getKnowledgeId, knowledgeId));
        return AjaxResult.success(count);
    }

    // 新增知识库
    @RequireRole("tenant_admin")
    @CacheEvict(value = "knowledge", allEntries = true)
    @PostMapping("/add")
    public AjaxResult addKnowledge(@RequestBody Knowledge knowledge) {
        knowledgeService.save(knowledge);
        return AjaxResult.success();
    }

    @RequireRole("tenant_admin")
    @PutMapping("/update/{knowledgeId}")
    @CacheEvict(value = "knowledge", allEntries = true)
    public AjaxResult updateKnowledge(@PathVariable Long knowledgeId, @RequestBody Knowledge knowledge) {
        knowledge.setId(knowledgeId);
        knowledgeService.updateById(knowledge);
        return AjaxResult.success();
    }

    @RequireRole("tenant_admin")
    @DeleteMapping("/delete/{knowledgeId}")
    @CacheEvict(value = "knowledge", allEntries = true)
    public AjaxResult deleteKnowledge(@PathVariable Long knowledgeId) {
        knowledgeService.removeById(knowledgeId);
        return AjaxResult.success();
    }

    @GetMapping("/{knowledgeId}")
    public AjaxResult getKnowledgeById(@PathVariable Long knowledgeId) {
        Knowledge knowledge = knowledgeService.getById(knowledgeId);
        return AjaxResult.success(knowledge);
    }

    @GetMapping("/list")
    public AjaxResult listAllKnowledge() {
        List<Knowledge> knowledgeList = knowledgeService.list();
        return AjaxResult.success(knowledgeList);
    }

    @Cacheable(value = "knowledge")
    @GetMapping("/list/vo")
    public AjaxResult listAllKnowledgeVo() {
        log.info("查询所有知识库列表 - " + System.currentTimeMillis());
        List<Knowledge> knowledgeList = knowledgeService.list();
        List<KnowledgeVo> list = knowledgeList.stream().map(k -> {
            long fileCount = knowledgeFileService.count(new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getKnowledgeId, k.getId()));
            long agentCount = agentKnowledgeService.count(new LambdaQueryWrapper<AgentKnowledge>().eq(AgentKnowledge::getKnowledgeId, k.getId()));
            KnowledgeVo knowledgeVo = new KnowledgeVo();
            BeanUtils.copyProperties(k, knowledgeVo, KnowledgeVo.class);
            knowledgeVo.setFileCount(fileCount);
            knowledgeVo.setAgentCount(agentCount);
            return knowledgeVo;
        }).toList();
        return AjaxResult.success(list);
    }


}
