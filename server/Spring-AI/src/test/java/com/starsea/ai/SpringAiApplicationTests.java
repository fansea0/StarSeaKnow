package com.starsea.ai;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.ai.embedding.EmbeddingClient;

@SpringBootTest
class SpringAiApplicationTests {

	@Test
	void contextLoads() {
	}

	/*@Resource
	private EmbeddingClient embeddingClient;

	@Test
	void embeddingDimensionsTest(){
		//打印embedding模型的转换向量的维度
		System.out.println(embeddingClient.dimensions());
	}*/
}
