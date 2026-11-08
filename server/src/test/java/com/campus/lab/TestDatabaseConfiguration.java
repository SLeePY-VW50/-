package com.campus.lab;

import java.net.URI;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@TestConfiguration
public class TestDatabaseConfiguration {

    @Bean
    SqlQueryCollector sqlQueryCollector() {
        return new SqlQueryCollector();
    }

    @Bean
    FlywayMigrationStrategy guardedTestMigration(
            Environment environment,
            @Value("${spring.datasource.url}") String jdbcUrl) {
        return flyway -> {
            if (!environment.acceptsProfiles(Profiles.of("test"))) {
                throw new IllegalStateException("测试迁移必须激活 test profile");
            }
            String databaseName = databaseName(jdbcUrl);
            if (!"lab_reservation_test".equals(databaseName)) {
                throw new IllegalStateException("拒绝在非测试数据库执行迁移: " + databaseName);
            }
            flyway.migrate();
        };
    }

    @Bean
    SmartInitializingSingleton guardedTestRedisDatabase(
            Environment environment, LettuceConnectionFactory connectionFactory) {
        return () -> {
            if (!environment.acceptsProfiles(Profiles.of("test"))) {
                throw new IllegalStateException("测试Redis必须激活 test profile");
            }
            if (connectionFactory.getDatabase() != 15) {
                throw new IllegalStateException("测试Redis必须使用独立逻辑数据库15");
            }
            var connection = connectionFactory.getConnection();
            try {
                connection.ping();
            } finally {
                connection.close();
            }
        };
    }

    private static String databaseName(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("测试数据库仅支持 MySQL JDBC URL");
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        String path = uri.getPath();
        if (path == null || path.length() < 2) {
            throw new IllegalStateException("JDBC URL 未指定数据库名");
        }
        return path.substring(1);
    }
}
