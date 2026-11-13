package com.example.backend.controller;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.UpdateProfileRequest;
import com.example.backend.dto.UserResponse;
import com.example.backend.entity.AppUser;
import com.example.backend.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AppUser user) {
        return userService.currentUser(user);
    }

    /**
     * Updates the signed-in user's profile.
     *
     * <p>Returns the same shape as login - a fresh token plus the user - because
     * changing the email address invalidates the claims inside the old token. The
     * frontend must store the returned token, or the header will keep showing the
     * previous name.</p>
     */
    /** Deletes the account with all saved analyses and feedback. */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal AppUser user) {
        userService.deleteAccount(user);
    }

    /** Downloads everything stored about the signed-in user as JSON. */
    @GetMapping("/me/export")
    public Map<String, Object> exportData(@AuthenticationPrincipal AppUser user) {
        return userService.exportData(user);
    }

    @PutMapping("/me")
    public AuthResponse updateProfile(
            @AuthenticationPrincipal AppUser user,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return userService.updateProfile(user, request);
    }
}
