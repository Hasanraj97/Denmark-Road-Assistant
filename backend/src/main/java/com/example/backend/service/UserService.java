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

@Service
public class UserService {

    private final AppUserRepository userRepository;
    private final JwtService jwtService;

    public UserService(AppUserRepository userRepository, JwtService jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
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
        user.setPhone(request.phone().trim());
        user.setAddress(request.address().trim());

        AppUser savedUser = userRepository.save(user);
        return new AuthResponse(jwtService.generateToken(savedUser), UserResponse.from(savedUser));
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
