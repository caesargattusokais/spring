package com.example.usage;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;
import static org.assertj.core.api.Assertions.assertThat;

@Aspect
@Component
class LogAspect {
    @Before("execution(* UserService2.getUser(..)) && args(id)")
    void before(Long id) { System.out.println(">> Before getUser:" + id); }

    @Around("execution(* UserService2.getUser(..))")
    Object around(ProceedingJoinPoint jp) throws Throwable {
        long t = System.currentTimeMillis();
        Object r = jp.proceed();
        System.out.println(">> cost " + (System.currentTimeMillis() - t) + "ms");
        return r;
    }

    @AfterReturning(value = "execution(* UserService2.getUser(..))", returning = "r")
    void afterReturn(Object r) { System.out.println(">> result: " + r); }
}

@Component
class UserService2 { String getUser(Long id) { return "User-" + id; } }

@Configuration
@EnableAspectJAutoProxy
@ComponentScan
class AopConfig {}

class AopExampleTest {
    @Test
    void testAop() {
        var ctx = new AnnotationConfigApplicationContext(AopConfig.class);
        var svc = ctx.getBean(UserService2.class);
        assertThat(svc.getUser(42L)).isEqualTo("User-42");
        ctx.close();
    }
}
