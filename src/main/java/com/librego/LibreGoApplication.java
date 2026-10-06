package com.librego;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableJpaRepositories(considerNestedRepositories = true)
public class LibreGoApplication {
    public static void main(String[] args) {
        SpringApplication.run(LibreGoApplication.class, args);
    }
}
