-- 智能体表
CREATE TABLE agent (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(32) NOT NULL,
    description VARCHAR(512),
    prologue VARCHAR(512),
    role_description VARCHAR(512),
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 为常用查询字段创建索引
CREATE INDEX idx_agent_name ON agent(name);

-- 知识库表
CREATE TABLE knowledge (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(32) NOT NULL,
    description VARCHAR(512),
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 为常用查询字段创建索引
CREATE INDEX idx_knowledge_name ON knowledge(name);

-- 文件表
CREATE TABLE file (
    id BIGSERIAL PRIMARY KEY,
    file_name VARCHAR(32) NOT NULL,
    size BIGINT NOT NULL,
    status SMALLINT NOT NULL DEFAULT 0 CHECK (status IN (0, 1)),
    type VARCHAR(32) NOT NULL,
    path VARCHAR(256) NOT NULL,
    embedding_status SMALLINT NOT NULL DEFAULT 0 CHECK (embedding_status IN (0, 1,2)),
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 为常用查询字段创建索引
CREATE INDEX idx_file_name ON file(file_name);
CREATE INDEX idx_file_status ON file(status);
CREATE INDEX idx_file_type ON file(type);

-- 智能体-知识库关联表
CREATE TABLE agent_knowledge (
    agent_id BIGINT NOT NULL,
    knowledge_id BIGINT NOT NULL,
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (agent_id, knowledge_id),
    CONSTRAINT fk_agent_knowledge_agent FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_knowledge_knowledge FOREIGN KEY (knowledge_id) REFERENCES knowledge(id) ON DELETE CASCADE
);

-- 知识库-文件关联表
CREATE TABLE knowledge_file (
    knowledge_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    PRIMARY KEY (knowledge_id, file_id),
    CONSTRAINT fk_knowledge_file_knowledge FOREIGN KEY (knowledge_id) REFERENCES knowledge(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_file_file FOREIGN KEY (file_id) REFERENCES file(id) ON DELETE CASCADE
);

-- 创建触发器函数，用于自动更新update_time
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.update_time = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 为需要自动更新时间的表添加触发器
CREATE TRIGGER trigger_update_agent_timestamp
BEFORE UPDATE ON agent
FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE TRIGGER trigger_update_knowledge_timestamp
BEFORE UPDATE ON knowledge
FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE TRIGGER trigger_update_file_timestamp
BEFORE UPDATE ON file
FOR EACH ROW EXECUTE FUNCTION update_timestamp();