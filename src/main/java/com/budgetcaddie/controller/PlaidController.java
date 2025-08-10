package com.budgetcaddie.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.budgetcaddie.repository.TransactionRepository;
import com.budgetcaddie.model.Transaction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/plaid")
public class PlaidController {

    @Value("${plaid.client_id}")
    private String clientId;

    @Value("${plaid.secret}")
    private String secret;

    @Value("${plaid.env}")
    private String plaidBaseUrl;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository transactionRepository;

    // Example endpoint to create a Link token
    @GetMapping("/create_link_token")
    public ResponseEntity<?> createLinkToken() {
        try {
            // ✅ Generate a temporary unique user ID (for demo/testing without user linking)
            // In the future, replace with your actual DB user ID
            String uniqueUserId = java.util.UUID.randomUUID().toString();

            Map<String, Object> user = new HashMap<>();
            user.put("client_user_id", uniqueUserId);

            Map<String, Object> request = new HashMap<>();
            request.put("client_id", clientId);
            request.put("secret", secret);
            request.put("client_name", "BudgetCaddie");
            request.put("language", "en");
            request.put("country_codes", new String[] { "US", "CA" });
            request.put("user", user);
            request.put("products", new String[] { "transactions" });

            String url = plaidBaseUrl + "/link/token/create";

            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                return ResponseEntity.ok(json);
            } else {
                return ResponseEntity.badRequest().body("Failed to create link token");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @PostMapping("/exchange_public_token")
    public ResponseEntity<?> exchangePublicToken(@RequestBody Map<String, String> body) {
        String publicToken = body.get("public_token");
        if (publicToken == null || publicToken.isEmpty()) {
            return ResponseEntity.badRequest().body("public_token is required");
        }

        try {
            // Build request body for exchange
            Map<String, String> request = new HashMap<>();
            request.put("client_id", clientId);
            request.put("secret", secret);
            request.put("public_token", publicToken);

            // Call Plaid exchange endpoint
            String url = plaidBaseUrl + "/item/public_token/exchange";
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return ResponseEntity.status(response.getStatusCode())
                        .body("Failed to exchange public token");
            }

            // Optional: parse response JSON to get access_token
            JsonNode json = objectMapper.readTree(response.getBody());
            String accessToken = json.path("access_token").asText();

            // TODO: Save accessToken securely per user in your DB here

            return ResponseEntity.ok(Map.of("access_token", accessToken));
        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + ex.getMessage());
        }
    }

    // @PostMapping("/transactions/get")
    // public ResponseEntity<?> getTransactions(@RequestBody Map<String, String> body) {
    //     String accessToken = body.get("access_token");

    //     if (accessToken == null || accessToken.isEmpty()) {
    //         return ResponseEntity.badRequest().body("access_token is required");
    //     }

    //     try {
    //         Map<String, Object> request = new HashMap<>();
    //         request.put("client_id", clientId);
    //         request.put("secret", secret);
    //         request.put("access_token", accessToken);

    //         // Optional: specify date range
    //         request.put("start_date", LocalDate.now().minusDays(30).toString());
    //         request.put("end_date", LocalDate.now().toString());

    //         String url = plaidBaseUrl + "/transactions/get";

    //         ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

    //         if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
    //             return ResponseEntity.status(response.getStatusCode()).body("Failed to fetch transactions");
    //         }

    //         // Return raw JSON response or parse and map as needed
    //         return ResponseEntity.ok(objectMapper.readTree(response.getBody()));

    //     } catch (Exception e) {
    //         e.printStackTrace();
    //         return ResponseEntity.internalServerError().body("Error fetching transactions: " + e.getMessage());
    //     }
    // }

    // /transactions/get
    @PostMapping("/transactions/get")
    public ResponseEntity<?> getTransactions(@RequestBody Map<String, String> body) {
        String accessToken = body.get("access_token");

        if (accessToken == null || accessToken.isEmpty()) {
            return ResponseEntity.badRequest().body("access_token is required");
        }

        try {
            Map<String, Object> request = new HashMap<>();
            request.put("client_id", clientId);
            request.put("secret", secret);
            request.put("access_token", accessToken);

            // Changed to last 2 months
            request.put("start_date", LocalDate.now().minusMonths(2).toString());
            request.put("end_date", LocalDate.now().toString());

            String url = plaidBaseUrl + "/transactions/get";
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return ResponseEntity.status(response.getStatusCode()).body("Failed to fetch transactions");
            }

            return ResponseEntity.ok(objectMapper.readTree(response.getBody()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error fetching transactions: " + e.getMessage());
        }
    }

    // // Ingest Plaid transactions and save in DB
    // @PostMapping("/transactions/ingest")
    // public ResponseEntity<?> ingestTransactions(@RequestBody Map<String, String> body) {
    //     String accessToken = body.get("access_token");
    //     if (accessToken == null || accessToken.isEmpty()) {
    //         return ResponseEntity.badRequest().body("access_token is required");
    //     }
    //     try {
    //         // Build Plaid API request
    //         Map<String, Object> request = new HashMap<>();
    //         request.put("client_id", clientId);
    //         request.put("secret", secret);
    //         request.put("access_token", accessToken);
    //         request.put("start_date", LocalDate.now().minusDays(30).toString());
    //         request.put("end_date", LocalDate.now().toString());

    //         String url = plaidBaseUrl + "/transactions/get";
    //         ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

    //         if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
    //             return ResponseEntity.status(response.getStatusCode()).body("Plaid fetch failed");
    //         }

    //         // Parse and save new transactions only
    //         JsonNode root = objectMapper.readTree(response.getBody());
    //         JsonNode txs = root.path("transactions");
    //         int count = 0;
    //         for (JsonNode t : txs) {
    //             String plaidId = t.path("transaction_id").asText();
    //             if (!transactionRepository.existsByPlaidTransactionId(plaidId)) {
    //                 Transaction tx = new Transaction();
    //                 tx.setPlaidTransactionId(plaidId);
    //                 tx.setAccountId(t.path("account_id").asText());
    //                 tx.setAmount(t.path("amount").asDouble());
    //                 tx.setName(t.path("name").asText());
    //                 JsonNode cat = t.path("personal_finance_category");
    //                 tx.setCategory(cat.path("primary").asText(null));
    //                 tx.setSubcategory(cat.path("detailed").asText(null));
    //                 tx.setDate(LocalDate.parse(t.path("date").asText()));
    //                 tx.setCurrencyCode(t.path("iso_currency_code").asText(null));
    //                 tx.setMerchantName(t.path("merchant_name").asText(null));
    //                 transactionRepository.save(tx);
    //                 count++;
    //             }
    //         }
    //         return ResponseEntity.ok("Stored " + count + " new transactions.");
    //     } catch (Exception e) {
    //         e.printStackTrace();
    //         return ResponseEntity.internalServerError().body("Error ingesting transactions: " + e.getMessage());
    //     }
    // }

    // /transactions/ingest
    @PostMapping("/transactions/ingest")
    public ResponseEntity<?> ingestTransactions(@RequestBody Map<String, String> body) {
        String accessToken = body.get("access_token");
        if (accessToken == null || accessToken.isEmpty()) {
            return ResponseEntity.badRequest().body("access_token is required");
        }
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("client_id", clientId);
            request.put("secret", secret);
            request.put("access_token", accessToken);

            // Changed to last 2 months
            request.put("start_date", LocalDate.now().minusMonths(2).toString());
            request.put("end_date", LocalDate.now().toString());

            String url = plaidBaseUrl + "/transactions/get";
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return ResponseEntity.status(response.getStatusCode()).body("Plaid fetch failed");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode txs = root.path("transactions");
            int count = 0;
            for (JsonNode t : txs) {
                String plaidId = t.path("transaction_id").asText();
                if (!transactionRepository.existsByPlaidTransactionId(plaidId)) {
                    Transaction tx = new Transaction();
                    tx.setPlaidTransactionId(plaidId);
                    tx.setAccountId(t.path("account_id").asText());
                    tx.setAmount(t.path("amount").asDouble());
                    tx.setName(t.path("name").asText());
                    JsonNode cat = t.path("personal_finance_category");
                    tx.setCategory(cat.path("primary").asText(null));
                    tx.setSubcategory(cat.path("detailed").asText(null));
                    tx.setDate(LocalDate.parse(t.path("date").asText()));
                    tx.setCurrencyCode(t.path("iso_currency_code").asText(null));
                    tx.setMerchantName(t.path("merchant_name").asText(null));
                    transactionRepository.save(tx);
                    count++;
                }
            }
            return ResponseEntity.ok("Stored " + count + " new transactions.");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error ingesting transactions: " + e.getMessage());
        }
    }

    @GetMapping("/transactions/all")
    public java.util.List<Transaction> getAllStoredTransactions() {
        return transactionRepository.findAll();
    }

}
