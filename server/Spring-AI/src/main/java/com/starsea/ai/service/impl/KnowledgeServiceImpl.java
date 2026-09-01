package com.starsea.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.starsea.ai.domain.Knowledge;
import com.starsea.ai.mapper.KnowledgeMapper;
import com.starsea.ai.service.KnowledgeService;
import org.springframework.stereotype.Service;

/**
* @author ASUS
* @description 针对表【knowledge】的数据库操作Service实现
* @createDate 2025-05-03 11:33:17
*/

@Service
public class KnowledgeServiceImpl extends ServiceImpl<KnowledgeMapper, Knowledge>
    implements KnowledgeService {
}



