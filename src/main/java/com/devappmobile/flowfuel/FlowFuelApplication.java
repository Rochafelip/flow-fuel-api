package com.devappmobile.flowfuel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FlowFuelApplication {

	public static void main(String[] args) {
		SpringApplication.run(FlowFuelApplication.class, args);
	}

}
