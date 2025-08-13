package com.budgetcaddie.repository;

import com.budgetcaddie.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByPlaidTransactionId(String plaidTransactionId);

    boolean existsByPlaidTransactionId(String plaidTransactionId);

    List<Transaction> findAllByUserId(Long userId);
}
