package com.example.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

@Configuration
@EnableTransactionManagement
@ComponentScan
class TxConfig {
    @Bean DataSource dataSource() {
        return new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("classpath:com/example/usage/schema.sql")
            .build();
    }
    @Bean PlatformTransactionManager txManager(DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
    @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
}

@Service
class OrderService {
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditService audit;

    @Transactional
    void createOrder(int id, String name) {
        jdbc.update("INSERT INTO orders VALUES (?,?)", id, name);
        try { audit.log(id); } catch (RuntimeException e) { /* ignore */ }
    }
}

@Service
class AuditService {
    @Autowired JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void log(int orderId) {
        jdbc.update("INSERT INTO audit_log VALUES (?,?)", orderId, "created");
        throw new RuntimeException("audit failed");
    }
}

class TxExampleTest {
    @Test
    void testRequiresNew() {
        var ctx = new AnnotationConfigApplicationContext(TxConfig.class);
        var svc = ctx.getBean(OrderService.class);
        var jdbc = ctx.getBean(JdbcTemplate.class);

        svc.createOrder(1, "order-1");

        int orders = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
        int audit = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);
        assertThat(orders).isEqualTo(1);  // 主事务提交成功
        assertThat(audit).isEqualTo(0);   // 审计回滚
        ctx.close();
    }
}
