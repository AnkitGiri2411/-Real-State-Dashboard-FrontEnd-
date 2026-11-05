package com.meridian.realestate.domain;

import java.io.Serializable;
import java.util.UUID;

/**
 * The composite key of a role assignment: which account, and which role it holds.
 *
 * <p>The table has no surrogate id, because the account-role pair is the identity of the assignment
 * - a person either holds a role or does not, and holding it twice is not a state worth being able
 * to express. A record suits that: the pair is immutable in practice (an assignment is added or
 * withdrawn, never re-pointed at another role), and it keeps the identity of the row explicit
 * rather than hidden behind a generated column the schema deliberately does not have.</p>
 */
public record UserRoleId(UUID userId, UUID roleId) implements Serializable {}
