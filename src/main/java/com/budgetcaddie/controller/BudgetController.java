package com.budgetcaddie.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/budgets")
public class BudgetController {
    @GetMapping("/hello")
    public String hello() {
        return "Hello, Budgets!";
    }
}
