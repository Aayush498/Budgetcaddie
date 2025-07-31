package com.budgetcaddie.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/goals")
public class GoalController {
    @GetMapping("/hello")
    public String hello() {
        return "Hello, Goals!";
    }
}
