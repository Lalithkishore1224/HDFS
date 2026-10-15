package com.cloudstorage.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findBySubject(String subject);

    Optional<UserAccount> findByUsername(String username);

    List<UserAccount> findByStatusOrderByCreatedAtAsc(AccountStatus status);

    List<UserAccount> findAllByOrderByCreatedAtAsc();
}
