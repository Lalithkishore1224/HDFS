package com.cloudstorage.admin;

import com.cloudstorage.account.AccountResponse;
import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.AccountStatus;
import com.cloudstorage.account.UserAccount;
import com.cloudstorage.file.FileRepository;
import com.cloudstorage.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The administrator's account queue: see who signed up, approve or reject them. */
@RestController
@RequestMapping("/api/admin/users")
class AdminUserController {

    /** An account plus how much of the drive it occupies. */
    record ManagedAccount(AccountResponse account, long usedBytes) {
    }

    private final AccountService accounts;
    private final FileRepository files;

    AdminUserController(AccountService accounts, FileRepository files) {
        this.accounts = accounts;
        this.files = files;
    }

    @GetMapping
    List<ManagedAccount> list(@RequestParam(required = false) AccountStatus status) {
        return accounts.list(status).stream().map(this::describe).toList();
    }

    @PostMapping("/{id}/approve")
    ManagedAccount approve(@CurrentUser UserAccount admin, @PathVariable UUID id) {
        return describe(accounts.approve(admin.getId(), id));
    }

    @PostMapping("/{id}/reject")
    ManagedAccount reject(@CurrentUser UserAccount admin, @PathVariable UUID id) {
        return describe(accounts.reject(admin.getId(), id));
    }

    private ManagedAccount describe(UserAccount account) {
        return new ManagedAccount(AccountResponse.from(account), files.totalSizeBytes(account.getId()));
    }
}
