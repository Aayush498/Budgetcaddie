package com.budgetcaddie;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication(scanBasePackages = "com.budgetcaddie")
public class BudgetcaddieApplication {

	public static void main(String[] args) {
		Dotenv.load();
		SpringApplication.run(BudgetcaddieApplication.class, args);
	}

}
