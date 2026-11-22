package com.hamiltel.gmlc.ops;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accounts. The create call is the only time a password or TOTP secret leaves
 * the server, and it returns them once; no read path ever includes them.
 */
@RestController
@RequestMapping("/v1/ops/users")
@OpsRequires(OpsPermission.MANAGE_USERS)
public class OpsUserController {

    static final int PASSWORD_MIN_LENGTH = 12;

    private final OpsUserService users;
    private final OpsSessionService sessions;

    public OpsUserController(OpsUserService users, OpsSessionService sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @GetMapping
    public List<UserView> list(@OpsCurrentSession OpsContext context) {
        return users.list(context.tenantId()).stream().map(UserView::of).toList();
    }

    @PostMapping
    public ResponseEntity<CreatedUserView> create(@OpsCurrentSession OpsContext context,
                                                  @Valid @RequestBody CreateUserRequest body) {
        OpsUserService.Created created = users.create(context.tenantId(), context, body.username(),
                body.displayName(), body.role(), body.enabled() == null || body.enabled());
        return ResponseEntity.status(HttpStatus.CREATED).body(CreatedUserView.of(created));
    }

    @PatchMapping("/{userId}")
    public UserView update(@OpsCurrentSession OpsContext context,
                           @PathVariable("userId") UUID userId,
                           @Valid @RequestBody UpdateUserRequest body) {
        return UserView.of(users.update(context.tenantId(), context, userId,
                Optional.ofNullable(body.role()), Optional.ofNullable(body.enabled())));
    }

    @PostMapping("/{userId}/sign-out")
    public ResponseEntity<Void> signOut(@OpsCurrentSession OpsContext context,
                                        @PathVariable("userId") UUID userId) {
        users.get(context.tenantId(), userId);
        sessions.revokeAll(context.tenantId(), context.user().userId(), userId, null, "ADMIN_SIGN_OUT");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/password")
    public ResponseEntity<Void> changePassword(@OpsCurrentSession OpsContext context,
                                               @PathVariable("userId") UUID userId,
                                               @Valid @RequestBody ChangePasswordRequest body) {
        users.changePassword(context.tenantId(), context, userId, body.password().toCharArray());
        return ResponseEntity.noContent().build();
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,64}",
                    message = "3 to 64 letters, digits, dot, underscore or dash") String username,
            @NotBlank @Size(max = 128) String displayName,
            @NotNull OpsRole role,
            Boolean enabled) {
    }

    public record UpdateUserRequest(OpsRole role, Boolean enabled) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(min = PASSWORD_MIN_LENGTH, max = 200,
                    message = "must be between 12 and 200 characters") String password) {
    }

    public record UserView(
            UUID userId,
            String username,
            String displayName,
            String role,
            boolean enabled,
            Instant lastLoginAt,
            Instant createdAt) {

        static UserView of(OpsUser user) {
            return new UserView(user.userId(), user.username(), user.displayName(),
                    user.role().name(), user.enabled(), user.lastLoginAt(), user.createdAt());
        }
    }

    public record CreatedUserView(UserView user, String oneTimePassword, String totpSecret) {

        static CreatedUserView of(OpsUserService.Created created) {
            return new CreatedUserView(UserView.of(created.user()), created.oneTimePassword(),
                    created.totpSecret());
        }
    }
}
