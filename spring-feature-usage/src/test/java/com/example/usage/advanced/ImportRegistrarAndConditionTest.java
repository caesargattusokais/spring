package com.example.usage.advanced;

import java.lang.annotation.*;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.AnnotationMetadata;
import static org.assertj.core.api.Assertions.assertThat;

// 自定义条件：仅当系统属性 enable.feature=true 时装配
class OnFeatureEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(EnableFeature.class.getName());
        String feature = (String) attrs.get("value");
        return "true".equals(ctx.getEnvironment().getProperty("enable." + feature));
    }
}

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OnFeatureEnabledCondition.class)
@interface EnableFeature {
    String value();
}

// ImportBeanDefinitionRegistrar: 按条件动态注册 BeanDefinition
class FeatureRegistrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(AnnotationMetadata meta, BeanDefinitionRegistry registry) {
        if (meta.isAnnotated(EnableFeature.class.getName())) {
            Map<String, Object> attrs = meta.getAnnotationAttributes(EnableFeature.class.getName());
            String feature = (String) attrs.get("value");
            if ("cache".equals(feature)) {
                registry.registerBeanDefinition("cacheService",
                    BeanDefinitionBuilder.rootBeanDefinition(CacheService.class).getBeanDefinition());
            } else if ("mq".equals(feature)) {
                registry.registerBeanDefinition("mqService",
                    BeanDefinitionBuilder.rootBeanDefinition(MqService.class).getBeanDefinition());
            }
        }
    }
}

// ImportSelector: 根据条件选择导入的配置类
class FeatureImportSelector implements ImportSelector {
    @Override
    public String[] selectImports(AnnotationMetadata meta) {
        // 可以根据 ClassPath 是否存在某个类来决定导入
        try {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
            return new String[]{JacksonConfig.class.getName()};
        } catch (ClassNotFoundException e) {
            return new String[0];
        }
    }
}

@Configuration class JacksonConfig {
    @Bean String jsonTool() { return "jackson-available"; }
}

@EnableFeature("cache")
@Import(FeatureRegistrar.class)
@Configuration class FeatureConfig {}

class CacheService { String get() { return "cached-value"; } }
class MqService { String send() { return "sent"; } }

class ImportRegistrarAndConditionTest {
    @Test
    void testConditionalAssembly() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getSystemProperties().put("enable.cache", "true");
        ctx.getEnvironment().getSystemProperties().put("enable.mq", "false");
        ctx.register(FeatureConfig.class);
        ctx.refresh();

        // cache 启用 → FeatureRegistrar 注册了 CacheService
        assertThat(ctx.getBean(CacheService.class)).isNotNull();
        assertThat(ctx.getBean(CacheService.class).get()).isEqualTo("cached-value");

        // mq 未启用 → MqService 未注册
        assertThat(ctx.containsBean("mqService")).isFalse();

        ctx.close();
    }
}
