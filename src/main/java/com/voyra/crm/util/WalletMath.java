package com.voyra.crm.util;

import com.voyra.crm.enums.WalletEntryType;

import java.math.BigDecimal;

/** The one balance rule: HOLD/HOLD_RELEASE move held only, everything else moves balance; never held < 0 or balance < held. */
public final class WalletMath {

    private WalletMath() {
    }

    public record State(BigDecimal balance, BigDecimal held) {
        public BigDecimal available() {
            return balance.subtract(held);
        }
    }

    public static State apply(BigDecimal balance, BigDecimal held, WalletEntryType type, BigDecimal amount) {
        boolean holdMove = type == WalletEntryType.HOLD || type == WalletEntryType.HOLD_RELEASE;
        State next = holdMove ? new State(balance, held.add(amount)) : new State(balance.add(amount), held);
        if (next.held().signum() < 0 || next.available().signum() < 0) {
            throw new IllegalStateException("Wallet posting " + type + " " + amount + " would leave balance "
                    + next.balance() + " and held " + next.held());
        }
        return next;
    }
}
