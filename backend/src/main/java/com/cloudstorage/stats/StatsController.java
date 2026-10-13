package com.cloudstorage.stats;

import com.cloudstorage.account.UserAccount;
import com.cloudstorage.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class StatsController {

    private final StatsService statsService;

    StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** Space used versus the quota; the only storage information shown to users. */
    @GetMapping("/storage")
    StatsService.StorageUsage usage(@CurrentUser UserAccount user) {
        return statsService.usage(user);
    }

    /** Operational numbers including tiering effects; not linked from the user interface. */
    @GetMapping("/admin/stats")
    StatsService.Stats stats() {
        return statsService.current();
    }
}
