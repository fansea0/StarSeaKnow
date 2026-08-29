package com.fansea.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fansea.ai.domain.File;
import com.fansea.ai.domain.Knowledge;
import com.fansea.ai.mapper.FileMapper;
import com.fansea.ai.mapper.KnowledgeMapper;
import com.fansea.ai.service.FileService;
import com.fansea.ai.service.KnowledgeService;
import com.fansea.ai.service.RagService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
* @author ASUS
* @description 针对表【knowledge】的数据库操作Service实现
* @createDate 2025-05-03 11:33:17
*/

@Service
public class KnowledgeServiceImpl extends ServiceImpl<KnowledgeMapper, Knowledge>
    implements KnowledgeService {

    private final RagService ragService;
    private final FileService fileService;

    @Lazy
    public KnowledgeServiceImpl(RagService ragService, FileService fileService, FileMapper fileMapper) {
        this.ragService = ragService;
        this.fileService = fileService;
    }


    @Override
    public void loadEmbedding(Long knowledgeId, Long fileId) {
        File file = fileService.getById(fileId);
        System.out.println(file.toString());
        try {
            ragService.vectorize(file,knowledgeId);
        }catch (Exception e){
            file.setEmbeddingStatus(1);
            fileService.updateById(file);
            return;
        }
        file.setEmbeddingStatus(2);
        fileService.updateById(file);
    }
}




