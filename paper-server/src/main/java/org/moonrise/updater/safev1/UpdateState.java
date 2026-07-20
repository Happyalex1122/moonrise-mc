package org.moonrise.updater.safev1;

public enum UpdateState {
    DISCOVERED,
    STAGED,
    COMPATIBLE,
    UNKNOWN,
    BLOCKED,
    PENDING_APPROVAL,
    BACKED_UP,
    APPLYING,
    BOOT_ATTEMPTED,
    AWAITING_HEALTH,
    HEALTHY,
    ROLLBACK_PENDING,
    ROLLING_BACK,
    ROLLED_BACK,
    RECOVERY_REQUIRED
}
