package com.fansea.ai;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication(scanBasePackages = {"com.fansea.ai", "com.fansea.ai", "com.fansea.platform"})
@EnableCaching
@MapperScan(basePackages = {"com.fansea.ai.mapper", "com.fansea.ai.openapi.credential"},
        annotationClass = Mapper.class)
public class SpringAiApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringAiApplication.class, args);
	}

}
