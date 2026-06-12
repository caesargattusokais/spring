package com.example.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import static org.assertj.core.api.Assertions.assertThat;

// 配置类：驱动 IoC 容器
@Configuration
@ComponentScan(basePackages = "com.example.usage")
class IoCConfig {
    @Bean String appName() { return "spring-demo"; }
    @Bean Integer appPort(String appName) { return appName.equals("spring-demo") ? 8080 : 9090; }
}

@Component
class LogService { String log(String msg) { return "[LOG] " + msg; } }

@Service
class UserService {
    @Autowired private LogService logService;
    String getUser(Long id) { return logService.log("getUser:" + id); }
}

class IoCExampleTest {
    @Test
    void testAnnotationConfig() {
        var ctx = new AnnotationConfigApplicationContext(IoCConfig.class);
        assertThat(ctx.getBean("appName")).isEqualTo("spring-demo");
        assertThat(ctx.getBean(Integer.class)).isEqualTo(8080);
        assertThat(ctx.getBean(LogService.class).log("hello")).isEqualTo("[LOG] hello");
        assertThat(ctx.getBean(UserService.class).getUser(1L)).isEqualTo("[LOG] getUser:1");
        ctx.close();
    }
}
