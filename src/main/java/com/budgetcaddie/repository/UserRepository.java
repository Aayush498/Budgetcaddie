package com.budgetcaddie.repository;

import com.budgetcaddie.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    // Additional query methods can be defined here if needed

}
