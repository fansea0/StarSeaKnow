package com.fanSEA.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication(scanBasePackages = {"com.fanSEA.ai", "com.fangsa.ai", "com.fangsa.platform"})
@EnableCaching
@MapperScan({"com.fanSEA.ai.mapper", "com.fangsa.ai.mapper"})
public class SpringAiApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringAiApplication.class, args);
	}

}
