package com.meridian.realestate.repository;

import com.meridian.realestate.domain.RefreshToken;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Issued sessions, held as hashes.
 *
 * <p>Finders here take the hash, never the token: the plain token exists only in the client's
 * cookie and in the response that issued it, and a repository that could look a row up by the
 * secret itself would be the one place the secret is written down.</p>
 *
 * <p>Nothing is deleted. A revoked session stays as a row so the account's session list can explain
 * what happened - "signed out from Chrome on Windows" - and so a rotation can be detected rather
 * than merely rejected.</p>
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  /** The session behind a presented token, revoked or not. */
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * The live session behind a presented token, which is what an actual refresh requires.
   *
   * <p>A revoked row is deliberately excluded here rather than checked in the caller, so a token
   * that has already been rotated can never be exchanged again by a caller that forgets to look.</p>
   */
  Optional<RefreshToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

  /**
   * Revokes every live session an account holds - "sign out everywhere", and the first half of a
   * token rotation.
   *
   * <p>The revocation is written straight to the table as one statement rather than by loading and
   * editing each row: it has to be atomic with the caller's transaction, and a loop over an
   * unknown number of sessions is exactly the work a lock wait makes expensive.</p>
   *
   * <p>It bypasses the persistence context, so a session already loaded in the same transaction
   * still looks live in memory and would be written back green on flush. Callers that also touch a
   * specific session in that transaction must edit it after this call, not before.</p>
   *
   * @param userId the account whose sessions are being ended
   * @param revokedAt the instant of the sign-out, supplied by the caller so a whole rotation shares
   *     one timestamp
   * @return the number of sessions revoked, which is what an audit entry reports
   */
  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :revokedAt "
          + "where t.user.id = :userId and t.revokedAt is null")
  int revokeAllLiveTokensForUser(
      @Param("userId") UUID userId, @Param("revokedAt") LocalDateTime revokedAt);

  /**
   * How many sessions an account currently holds.
   *
   * <p>Expiry is part of the definition of live: a row whose window has passed is not a session
   * anyone can use, so counting it would overstate the account's exposure on the security screen.</p>
   */
  @Query(
      "select count(t) from RefreshToken t "
          + "where t.user.id = :userId and t.revokedAt is null and t.expiresAt > :now")
  long countLiveTokensForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
