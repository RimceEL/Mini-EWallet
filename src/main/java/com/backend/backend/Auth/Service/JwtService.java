package com.backend.backend.Auth.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JwtService {

    @Value("${jwt.secret}")
    private String SERECT_KEY;

    @Value("${jwt.access.expiration}")
    private long JWT_ACCESS_TOKEN_EXPIRATION;

    @Value("${jwt.refresh.expiration}")
    private long JWT_REFRESH_TOKEN_EXPIRATION;

    public String accessTokenGenerate(UserDetails userDetails, String jti) {
        HashMap<String, Object> claims = new HashMap<>();
        claims.put("roles", userDetails.getAuthorities());
        claims.put("jti", jti);
        return buildToken(claims, userDetails.getUsername(), JWT_ACCESS_TOKEN_EXPIRATION);
    }

    public String refreshTokenGenerate(UserDetails userDetails, String jti) {
        HashMap<String, Object> claims = new HashMap<>();
        claims.put("jti", jti);
        return buildToken(claims, userDetails.getUsername(), JWT_REFRESH_TOKEN_EXPIRATION);
    }

    public String refreshTokenGenerate(UserDetails userDetails, String jti, long expiration) {
        HashMap<String, Object> claims = new HashMap<>();
        claims.put("jti", jti);
        return buildToken(claims, userDetails.getUsername(), expiration);
    }

    private String buildToken(HashMap<String, Object> claims, String email, long expiration) {
        return Jwts
                .builder()
                .claims(claims)
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSignInKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignInKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractJti(String token) {
        return extractClaim(token, claims -> claims.get("jti", String.class));
    }

    public Date extractIssued(String token) {
        return extractClaim(token, Claims::getIssuedAt);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public List<String> extractClaimList(String token, String claimName) {
        Claims claims = extractAllClaims(token);
        List<?> rawList = claims.get(claimName, List.class);
        if (rawList == null) {
            return new ArrayList<>();
        }
        return rawList.stream()
                .map(Object::toString)
                .collect(Collectors.toList());
    }

    public boolean isExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isExpired(token));
    }

    private SecretKey getSignInKey() {
        byte[] keyBytes = SERECT_KEY.getBytes();
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public List<String> extractRoles(String token) {
        Claims claims = extractAllClaims(token);
        List<Map<String, String>> rolesClaim = claims.get("roles", List.class);

        if (rolesClaim == null) {
            return new ArrayList<>();
        }
        return rolesClaim.stream()
                .map(roleMap -> roleMap.get("authority"))
                .collect(Collectors.toList());
    }

}
