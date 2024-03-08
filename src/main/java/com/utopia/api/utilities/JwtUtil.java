package com.utopia.api.utilities;

import com.utopia.api.dao.UsersDAO;
import com.utopia.api.entities.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.sql.Timestamp;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final UsersDAO usersDAO;

    public JwtUtil(JdbcTemplate jdbcTemplate, @Value("${jwt.secret}") String secretKey) {
        this.usersDAO = new UsersDAO(jdbcTemplate);

        //The secretKey parameter should be removed in the production mode.
        //This line should be commented out in the production mode.
        this.key = Keys.hmacShaKeyFor(secretKey.getBytes());

        // This line should be enabled in the production mode. Because it generates more secure key.
        // this.key = Keys.secretKeyFor(io.jsonwebtoken.SignatureAlgorithm.HS256);
    }


    public String generateToken(User user) {
        Date now = new Date();

        Timestamp authTime = new Timestamp(now.getTime());
        CompletableFuture<Void> setAuthTimeFuture = CompletableFuture.runAsync(() -> {
            usersDAO.setAuthTime(user.getId(), authTime);
        });
        setAuthTimeFuture.join();

        Timestamp authTimeFromDb = usersDAO.getAuthTime(user.getId());

        long expirationTimeMillis = 24 * (60 * 60 * 1000); // 24 hours in milliseconds
        Date expirationDate = new Date(authTimeFromDb.getTime() + expirationTimeMillis);

        return Jwts.builder()
                .claim("userId", user.getId())
                .claim("userRole", user.getRole())
                .setIssuedAt(authTimeFromDb)
                .setExpiration(expirationDate)
                .signWith(key)
                .compact();
    }

    public JwtChecked validate(String token) {
        // Here jwtChecked.isValid = false;
        JwtChecked jwtChecked = new JwtChecked();

        if (token == null || token.isEmpty()) {
            System.err.println("Error validating JWT: Token is required!");
            return jwtChecked;
        }

        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();

            if (!claims.containsKey("userId")) {
                System.err.println("Error validating JWT: Missing userId claim");
                return jwtChecked;
            }
            if (!claims.containsKey("userRole")) {
                System.err.println("Error validating JWT: Missing userRole claim");
                return jwtChecked;
            }

            long userId = Long.parseLong(claims.get("userId").toString());
            if (!usersDAO.exists(userId)) {
                System.err.println("Error validating JWT: User doesn't exist or might be deleted!");
                return jwtChecked;
            }

            String userRole = claims.get("userRole").toString();
            if (!userRole.equals("user")){
                String existingUserRole = usersDAO.getRole(userId);
                if(existingUserRole == null) {
                    System.err.println("'role' field is null in the database!!!");
                    return jwtChecked;
                }
                if(!existingUserRole.equals(userRole) || (!userRole.equals("owner") && !userRole.equals("admin"))) {
                    System.err.println("Error validating JWT: User role mismatch!");
                    return jwtChecked;
                }
            }

            // Check if auth time from the database matches with the issued time of the token
            // If they don't match, it means that the user changed password
            Timestamp authTimeFromDB = usersDAO.getAuthTime(userId);
            Date authTime = new Date(authTimeFromDB.getTime());
            Date issuedAt = claims.getIssuedAt();
            if (authTime.compareTo(issuedAt) != 0) {
                System.out.println("AuthTime: " + authTime);
                System.out.println("TokenTime: " + issuedAt);
                System.err.println("Error validating JWT: Token generation time does not match auth time!");
                return jwtChecked;
            }

            jwtChecked.isValid = true;
            jwtChecked.userId = userId;
            jwtChecked.userRole = userRole;
            return jwtChecked;
        } catch (ExpiredJwtException e) {
            System.err.println("JWT token expired: " + e.getMessage());
            return jwtChecked;
        } catch (MalformedJwtException e) {
            System.err.println("Malformed JWT token: " + e.getMessage());
            return jwtChecked;
        } catch (Exception e) {
            System.err.println("Error validating JWT token: " + e.getMessage());
            return jwtChecked;
        }
    }

//    public long extractUserId(String token) {
//        Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
//        return Long.parseLong(claims.get("userId").toString());
//    }
//
//    public String extractUserRole(String token) {
//        Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
//        return claims.get("userRole").toString();
//    }

}