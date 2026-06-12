package com.example.usage.advanced;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;
import static org.assertj.core.api.Assertions.assertThat;

// BeanFactoryPostProcessor: 在 Bean 实例化之前修改 BeanDefinition
class ModifyBeanDefBfpp implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // 修改 @Scope：把所有 prototype 改为 singleton
        for (String name : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(name);
            if (bd.isPrototype()) {
                System.out.println("[BFPP] Change scope: " + name + " prototype -> singleton");
                bd.setScope(BeanDefinition.SCOPE_SINGLETON);
            }
        }
    }
}

// BeanDefinitionRegistryPostProcessor: 比 BFPP 更早，可以注册新的 BeanDefinition
class RegisterNewBeanDto implements BeanDefinitionRegistryPostProcessor {
    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        System.out.println("[BDRPP] Registering dynamic bean: dynamicBean");
        registry.registerBeanDefinition("dynamicBean",
            BeanDefinitionBuilder.rootBeanDefinition(DynamicBean.class)
                .addPropertyValue("name", "created-by-bdrpp")
                .getBeanDefinition());
    }
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}
}

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
class PrototypeBean {
    String sayHi() { return "hi"; }
}

class DynamicBean {
    private String name;
    void setName(String n) { this.name = n; }
    String getName() { return name; }
}

@Configuration
class BfppConfig {
    @Bean static ModifyBeanDefBfpp modifyBfpp() { return new ModifyBeanDefBfpp(); }
    @Bean static RegisterNewBeanDto registerDto() { return new RegisterNewBeanDto(); }
}

class BeanFactoryPostProcessorTest {
    @Test
    void testBfppAndBdrpp() {
        var ctx = new AnnotationConfigApplicationContext(BfppConfig.class, PrototypeBean.class);

        // BDRPP 动态注册的 bean
        var db = ctx.getBean("dynamicBean", DynamicBean.class);
        assertThat(db.getName()).isEqualTo("created-by-bdrpp");

        // BFPP 把 prototype 改成了 singleton
        var b1 = ctx.getBean(PrototypeBean.class);
        var b2 = ctx.getBean(PrototypeBean.class);
        assertThat(b1).isSameAs(b2); // 原型变成了单例

        ctx.close();
    }
}
