package com.example.backend.service;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.UpdateProfileRequest;
import com.example.backend.dto.UserResponse;
import com.example.backend.entity.AppUser;
import com.example.backend.exception.DuplicateEmailException;
import com.example.backend.repository.AppUserRepository;
import com.example.backend.security.JwtService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UserService {

    private final AppUserRepository userRepository;
    private final JwtService jwtService;
    private final RouteHistoryService routeHistoryService;

    public UserService(AppUserRepository userRepository, JwtService jwtService,
                       RouteHistoryService routeHistoryService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.routeHistoryService = routeHistoryService;
    }

    /**
     * Deletes the account and everything linked to it: every saved analysis and
     * every piece of feedback. Nothing is kept afterwards (GDPR right to erasure).
     */
    @Transactional
    public void deleteAccount(AppUser user) {
        routeHistoryService.deleteAllForUser(user);
        userRepository.deleteById(user.getId());
    }

    /**
     * Everything the app stores about this user, as one JSON document
     * (GDPR right of access and data portability). The password hash is never
     * included.
     */
    public Map<String, Object> exportData(AppUser user) {
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exportedAt", Instant.now().toString());
        export.put("profile", UserResponse.from(user));
        export.put("savedAnalyses", routeHistoryService.historyForUser(user));
        return export;
    }

    public UserResponse currentUser(AppUser user) {
        return UserResponse.from(user);
    }

    /**
     * Saves profile changes and hands back a <em>fresh</em> token.
     *
     * <p>This used to return the updated user only. Combined with the token
     * subject being the email address, that meant editing your own email logged
     * you out of your own account: the browser kept sending a token naming an
     * address that no longer existed, and the next request failed.</p>
     *
     * <p>Two changes fix it together. The token now identifies the user by their
     * database id (see {@link JwtService}), which cannot change; and this method
     * returns a newly signed token so the frontend can replace the stored one.
     * Either change alone would leave a gap - the id makes the old token keep
     * working, and the re-issue keeps the {@code fullName} and {@code email}
     * claims inside the token honest.</p>
     */
    @Transactional
    public AuthResponse updateProfile(AppUser user, UpdateProfileRequest request) {
        String normalizedEmail = normalizeEmail(request.email());

        userRepository.findByEmailIgnoreCase(normalizedEmail)
                .filter(existing -> !existing.getId().equals(user.getId()))
                .ifPresent(existing -> {
                    throw new DuplicateEmailException("That email is already used by another account.");
                });

        user.setFullName(request.fullName().trim());
        user.setEmail(normalizedEmail);
        user.setPhone(request.phone() == null || request.phone().isBlank() ? null : request.phone().trim());
        user.setAddress(request.address() == null || request.address().isBlank() ? null : request.address().trim());

        AppUser savedUser = userRepository.save(user);
        return new AuthResponse(jwtService.generateToken(savedUser), UserResponse.from(savedUser));
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
