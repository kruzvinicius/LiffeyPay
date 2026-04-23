package com.liffeypay.liffeypay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class LiffeyPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiffeyPayApplication.class, args);
    }

}