package com.gongu.server.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record StoreAdminLoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {}
