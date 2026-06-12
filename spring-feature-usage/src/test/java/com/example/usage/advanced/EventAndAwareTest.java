package com.example.usage.advanced;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import static org.assertj.core.api.Assertions.assertThat;

// 自定义事件
class DbInitEvent extends ApplicationEvent {
    private final String dbName;
    DbInitEvent(Object source, String dbName) { super(source); this.dbName = dbName; }
    String dbName() { return dbName; }
}

// Aware 接口链：Bean 感知容器的各种信息
@Component
class AwareBean implements BeanNameAware, BeanFactoryAware,
        BeanClassLoaderAware, ApplicationContextAware, ApplicationEventPublisherAware {
    final List<String> awareLog = new ArrayList<>();
    @Override public void setBeanName(String name) { awareLog.add("BeanName:" + name); }
    @Override public void setBeanFactory(BeanFactory bf) { awareLog.add("BeanFactory:" + bf); }
    @Override public void setBeanClassLoader(ClassLoader cl) { awareLog.add("ClassLoader:" + cl); }
    @Override public void setApplicationContext(ApplicationContext ctx) {
        awareLog.add("AppContext:" + ctx.getId()); }
    @Override public void setApplicationEventPublisher(ApplicationEventPublisher p) {
        awareLog.add("EventPublisher:" + p);
        p.publishEvent(new DbInitEvent(this, "mydb"));  // callback内部发事件
    }
    List<String> getAwareLog() { return awareLog; }
}

// @EventListener annotation 替代 ApplicationListener interface
@Component
class DbInitHandler {
    String eventDb;
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void onDbInit(DbInitEvent event) { this.eventDb = event.dbName(); }
    @EventListener(ContextRefreshedEvent.class)
    void onRefresh() { System.out.println("[Event] Container refreshed"); }
}

@Configuration
@ComponentScan
class EventAwareConfig {}

class EventAndAwareTest {
    @Test
    void testAwareAndEvent() {
        var ctx = new AnnotationConfigApplicationContext(EventAwareConfig.class);
        var aware = ctx.getBean(AwareBean.class);
        List<String> log = aware.getAwareLog();
        assertThat(log).anyMatch(s -> s.startsWith("BeanName:"));
        assertThat(log).anyMatch(s -> s.startsWith("BeanFactory:"));
        assertThat(log).anyMatch(s -> s.startsWith("AppContext:"));
        assertThat(log).anyMatch(s -> s.startsWith("EventPublisher:"));
        assertThat(ctx.getBean(DbInitHandler.class).eventDb).isEqualTo("mydb");
        ctx.close();
    }
}
