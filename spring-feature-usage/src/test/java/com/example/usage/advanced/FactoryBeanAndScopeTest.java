package com.example.usage.advanced;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.CustomScopeConfigurer;
import org.springframework.beans.factory.config.Scope;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientFactoryBean implements FactoryBean<HttpClient> {
    private String url;
    private int timeout;
    void setUrl(String u) { this.url = u; }
    void setTimeout(int t) { this.timeout = t; }
    @Override public HttpClient getObject() { return new HttpClient(url, timeout); }
    @Override public Class<?> getObjectType() { return HttpClient.class; }
    @Override public boolean isSingleton() { return true; }
}

class HttpClient {
    private final String url;
    private final int timeout;
    HttpClient(String url, int timeout) { this.url = url; this.timeout = timeout; }
    String call() { return "resp-" + url + "-t" + timeout; }
}

// ThreadLocalScope
class ThreadLocalScope implements Scope {
    private final ThreadLocal<Map<String, Object>> beans =
            ThreadLocal.withInitial(ConcurrentHashMap::new);
    @Override public Object get(String name, ObjectFactory<?> factory) {
        return beans.get().computeIfAbsent(name, k -> factory.getObject());
    }
    @Override public Object remove(String name) { return beans.get().remove(name); }
    @Override public void registerDestructionCallback(String name, Runnable cb) {}
    @Override public Object resolveContextualObject(String key) { return null; }
    @Override public String getConversationId() { return Thread.currentThread().getName(); }
}

@Component
@org.springframework.context.annotation.Scope("threadLocal")
class ScopedService {
    private final String id = "inst-" + System.identityHashCode(this);
    String getId() { return id; }
}

@Configuration(proxyBeanMethods = false)
class FactoryScopeConfig {
    // FactoryBean bean：Spring 检测到返回类型实现 FactoryBean，自动调用 getObject()
    @Bean HttpClientFactoryBean httpClient() {
        HttpClientFactoryBean fb = new HttpClientFactoryBean();
        fb.setUrl("https://api.example.com");
        fb.setTimeout(5000);
        return fb;
    }
    @Bean static CustomScopeConfigurer scopeCfg() {
        CustomScopeConfigurer c = new CustomScopeConfigurer();
        c.addScope("threadLocal", new ThreadLocalScope());
        return c;
    }
}

class FactoryBeanAndScopeTest {
    @Test
    void testFactoryBean() {
        var ctx = new AnnotationConfigApplicationContext(FactoryScopeConfig.class);
        // getBean(HttpClient.class) 内部通过 FactoryBean.getObject() 获取
        HttpClient c = ctx.getBean(HttpClient.class);
        assertThat(c.call()).isEqualTo("resp-https://api.example.com-t5000");
        // &httpClient 获取 FactoryBean 本身
        HttpClientFactoryBean fb = (HttpClientFactoryBean) ctx.getBean("&httpClient");
        assertThat(fb.getObjectType()).isEqualTo(HttpClient.class);
        ctx.close();
    }

    @Test
    void testCustomScope() throws Exception {
        var ctx = new AnnotationConfigApplicationContext(FactoryScopeConfig.class,
                ScopedService.class);
        ScopedService s1 = ctx.getBean(ScopedService.class);
        ScopedService s2 = ctx.getBean(ScopedService.class);
        assertThat(s1).isSameAs(s2); // same thread -> same instance
        // different thread -> different instance
        ScopedService[] holder = new ScopedService[1];
        Thread t = new Thread(() -> {
            holder[0] = ctx.getBean(ScopedService.class);
        });
        t.start(); t.join();
        assertThat(s1).isNotSameAs(holder[0]);
        ctx.close();
    }
}
