package com.budgetcaddie;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.budgetcaddie")
public class BudgetcaddieApplication {

	public static void main(String[] args) {
		SpringApplication.run(BudgetcaddieApplication.class, args);
	}

}
