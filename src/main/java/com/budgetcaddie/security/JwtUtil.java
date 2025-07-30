package com.budgetcaddie.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.function.Function;

@Component
public class JwtUtil {

    // Replace this with your own strong Base64-encoded secret key string,
    // preferably stored securely, e.g., in environment or config server
    private static final String SECRET = "ReplaceThisWithASecureLongBase64StringForYourJWTSecretKey12345";

    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET.getBytes());

    // Token validity duration in milliseconds (example: 10 hours)
    private final long jwtExpirationMs = 10 * 60 * 60 * 1000;

    /**
     * Generate JWT token containing username as subject, with expiration.
     */
    public String generateToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(SECRET_KEY)
                .compact();
    }

    /**
     * Extract username (subject) from JWT token.
     */
    public String extractUsername(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    /**
     * Generic method to extract a claim from a token.
     */
    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = parseClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Validate the token for expiration and signature.
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return claims.getExpiration().after(new Date());
        } catch (Exception e) {
            // Could log the exception here if needed
            return false;
        }
    }

    /**
     * Parse the Claims from the JWT token.
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(SECRET_KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
