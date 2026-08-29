package com.starsea.ai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * @Projectname: Spring-AI
 * @Filename: ServerTool
 * @Author: FANSEA
 * @Date:2025/4/5 15:37
 */
public class ServerTool {

    @Tool(description = "什么地方有多少个人")
    public String LocationName(@ToolParam(description = "地区或者城市") String location, @ToolParam(description = "姓名称号") String name){
        return "有10个人！";
    }
}
