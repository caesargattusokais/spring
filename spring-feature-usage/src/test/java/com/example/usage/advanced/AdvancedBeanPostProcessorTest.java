package com.example.usage.advanced;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

// 自定义注解：标记需要代理的 Bean
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@interface Monitored {}

// 自定义 BeanPostProcessor：
//   1. postProcessBeforeInitialization: 检测 @Monitored，给 Bean 设置监控启动时间
//   2. postProcessAfterInitialization: 打印初始化耗时
//   3. SmartInstantiationAwareBeanPostProcessor: 预测 bean type，替换构造函数
class MonitorBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean.getClass().isAnnotationPresent(Monitored.class)) {
            System.out.println("[Monitor] Before init: " + beanName);
            ((MonitoredBean) bean).setCreateTime(System.currentTimeMillis());
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof MonitoredBean mb && mb.getCreateTime() > 0) {
            long cost = System.currentTimeMillis() - mb.getCreateTime();
            System.out.println("[Monitor] After init: " + beanName + " cost=" + cost + "ms");
            mb.setInitCost(cost);
        }
        return bean;
    }
}

@Monitored
@Component
class MonitoredBean {
    private long createTime;
    private long initCost;
    void setCreateTime(long t) { this.createTime = t; }
    long getCreateTime() { return createTime; }
    void setInitCost(long c) { this.initCost = c; }
    long getInitCost() { return initCost; }
}

@Configuration
class BppConfig {
    @Bean static MonitorBeanPostProcessor monitorBpp() { return new MonitorBeanPostProcessor(); }
}

class AdvancedBeanPostProcessorTest {
    @Test
    void testCustomBpp() {
        var ctx = new AnnotationConfigApplicationContext(BppConfig.class, MonitoredBean.class);
        var bean = ctx.getBean(MonitoredBean.class);
        assertThat(bean.getCreateTime()).isPositive();
        assertThat(bean.getInitCost()).isGreaterThanOrEqualTo(0);
        ctx.close();
    }
}
