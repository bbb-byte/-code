package com.ecommerce.analysis;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 电商用户消费行为分析系统启动类
 * 
 * @author leiminghao
 * @date 2026-01-09
 */
@SpringBootApplication
@MapperScan("com.ecommerce.analysis.mapper")
@EnableAsync
@EnableScheduling
public class AnalysisApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalysisApplication.class, args);
        System.out.println("============================================");
        System.out.println("  电商用户消费行为分析系统启动成功！");
        System.out.println("  API文档: http://localhost:8080/api/swagger-ui.html");
        System.out.println("============================================");
    }
}
