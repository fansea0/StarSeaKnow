package com.starsea.ai;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication(scanBasePackages = {"com.starsea.ai", "com.starsea.ai", "com.starsea.platform"})
@EnableCaching
@MapperScan(basePackages = {"com.starsea.ai.mapper", "com.starsea.ai.openapi.credential"},
        annotationClass = Mapper.class)
public class SpringAiApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringAiApplication.class, args);
	}

}
