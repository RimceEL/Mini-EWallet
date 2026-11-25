package com.backend.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisConfigTest {

    @Test
    @DisplayName("RedisConfig: khởi tạo LettuceConnectionFactory (SSL bật) và StringRedisTemplate đúng property")
    void redisConfig_bindsPropertiesAndCreatesBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(RedisConfig.class)
                .withPropertyValues(
                        "spring.data.redis.host=redis.example.test",
                        "spring.data.redis.port=21911",
                        "spring.data.redis.username=default",
                        "spring.data.redis.password=secret-pass")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RedisConnectionFactory factory = context.getBean(RedisConnectionFactory.class);
                    assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);

                    LettuceConnectionFactory lettuce = (LettuceConnectionFactory) factory;
                    assertThat(lettuce.getHostName()).isEqualTo("redis.example.test");
                    assertThat(lettuce.getPort()).isEqualTo(21911);
                    assertThat(lettuce.getClientConfiguration().isUseSsl()).isTrue();
                    assertThat(context.getBean(StringRedisTemplate.class).getConnectionFactory()).isSameAs(factory);
                });
    }
}
