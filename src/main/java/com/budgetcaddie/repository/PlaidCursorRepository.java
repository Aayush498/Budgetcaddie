package com.budgetcaddie.repository;

import com.budgetcaddie.model.PlaidCursor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlaidCursorRepository extends JpaRepository<PlaidCursor, Long> {
    Optional<PlaidCursor> findByAccessToken(String accessToken);
}
