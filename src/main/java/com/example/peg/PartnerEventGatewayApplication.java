package com.example.peg;

import com.example.peg.platform.ConsumerProperties;
import com.example.peg.platform.RuntimeProperties;
import com.example.peg.platform.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        ConsumerProperties.class,
        RuntimeProperties.class,
        SecurityProperties.class
})
public class PartnerEventGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PartnerEventGatewayApplication.class, args);
    }
}
