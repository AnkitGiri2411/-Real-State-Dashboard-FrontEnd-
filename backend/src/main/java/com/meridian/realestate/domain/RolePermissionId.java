package com.meridian.realestate.domain;

import java.io.Serializable;
import java.util.UUID;

/**
 * The composite key of a role-permission grant: which role, and which capability it was given.
 *
 * <p>The table has no surrogate id, because the pair <em>is</em> the identity of the grant - a role
 * either has a capability or it does not, and there is no third state to key on. A record suits
 * that: the pair is immutable in practice (a grant is added or withdrawn, never re-pointed at
 * another capability), and it keeps the identity of the row explicit rather than hidden behind a
 * generated column the schema deliberately does not have.</p>
 */
public record RolePermissionId(UUID roleId, UUID permissionId) implements Serializable {}
