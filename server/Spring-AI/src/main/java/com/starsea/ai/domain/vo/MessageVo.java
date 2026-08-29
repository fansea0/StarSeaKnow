package com.fansea.ai.domain.vo;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;

/**
 * @Projectname: Spring-AI
 * @Filename: MessageVo
 * @Author: FANSEA
 * @Date:2025/4/4 23:22
 */
@Data
public class MessageVo {
    private String role;
    private String content;

    // 构造器
    public MessageVo(Message message) {
        switch (message.getMessageType()) {
            case USER:
                this.role = "user";break;
            case ASSISTANT:
                this.role = "assistant";break;
            default:
                this.role = "";break;
        }
        this.content = message.getText();
    }
}
