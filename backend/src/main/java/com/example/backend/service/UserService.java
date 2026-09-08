package com.example.backend.service;

import com.example.backend.dto.UpdateProfileRequest;
import com.example.backend.dto.UserResponse;
import com.example.backend.entity.AppUser;
import com.example.backend.exception.DuplicateEmailException;
import com.example.backend.repository.AppUserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final AppUserRepository userRepository;

    public UserService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserResponse currentUser(AppUser user) {
        return UserResponse.from(user);
    }

    public UserResponse updateProfile(AppUser user, UpdateProfileRequest request) {
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

        return UserResponse.from(userRepository.save(user));
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
