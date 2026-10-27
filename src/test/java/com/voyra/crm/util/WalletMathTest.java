package com.voyra.crm.util;

import com.voyra.crm.enums.WalletEntryType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletMathTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void holdMovesHeldOnly() {
        WalletMath.State held = WalletMath.apply(d("1000"), d("0"), WalletEntryType.HOLD, d("400"));
        assertThat(held.balance()).isEqualByComparingTo("1000");
        assertThat(held.held()).isEqualByComparingTo("400");

        WalletMath.State released = WalletMath.apply(held.balance(), held.held(), WalletEntryType.HOLD_RELEASE, d("-400"));
        assertThat(released.balance()).isEqualByComparingTo("1000");
        assertThat(released.held()).isEqualByComparingTo("0");
    }

    @Test
    void bookingDebitAfterReleaseLowersBalance() {
        WalletMath.State released = WalletMath.apply(d("1000"), d("400"), WalletEntryType.HOLD_RELEASE, d("-400"));
        WalletMath.State debited = WalletMath.apply(released.balance(), released.held(), WalletEntryType.BOOKING_DEBIT, d("-400"));
        assertThat(debited.balance()).isEqualByComparingTo("600");
        assertThat(debited.held()).isEqualByComparingTo("0");
    }

    @Test
    void aPostingThatWouldGoNegativeIsRefused() {
        assertThatThrownBy(() -> WalletMath.apply(d("100"), d("0"), WalletEntryType.BOOKING_DEBIT, d("-101")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDebitThatWouldEatIntoHeldMoneyIsRefused() {
        assertThatThrownBy(() -> WalletMath.apply(d("100"), d("60"), WalletEntryType.BOOKING_DEBIT, d("-41")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void randomSequencesNeverBreakTheInvariants() {
        WalletEntryType[] types = WalletEntryType.values();
        for (int seed = 0; seed < 500; seed++) {
            Random random = new Random(seed);
            BigDecimal balance = BigDecimal.ZERO;
            BigDecimal held = BigDecimal.ZERO;
            BigDecimal nonHoldSum = BigDecimal.ZERO;
            List<BigDecimal> openHolds = new ArrayList<>();

            for (int step = 0; step < 30; step++) {
                WalletEntryType type = types[random.nextInt(types.length)];
                BigDecimal size = BigDecimal.valueOf(random.nextInt(200_000) + 1, 2); // 0.01 .. 2000.00
                BigDecimal amount;
                Integer releasedIndex = null;
                switch (type) {
                    case HOLD, TOPUP, COMMISSION_CREDIT, REFUND_CREDIT -> amount = size;
                    case HOLD_RELEASE -> {
                        if (openHolds.isEmpty()) {
                            continue;
                        }
                        releasedIndex = random.nextInt(openHolds.size());
                        amount = openHolds.get(releasedIndex).negate();
                    }
                    case ADJUSTMENT -> amount = random.nextBoolean() ? size : size.negate();
                    default -> amount = size.negate(); // BOOKING_DEBIT, TDS, recall, settled, closure
                }

                WalletMath.State next;
                try {
                    next = WalletMath.apply(balance, held, type, amount);
                } catch (IllegalStateException refused) {
                    continue;
                }
                balance = next.balance();
                held = next.held();
                if (type == WalletEntryType.HOLD) {
                    openHolds.add(amount);
                } else if (releasedIndex != null) {
                    openHolds.remove((int) releasedIndex);
                } else {
                    nonHoldSum = nonHoldSum.add(amount);
                }

                assertThat(held.signum()).as("seed %d step %d held >= 0", seed, step).isGreaterThanOrEqualTo(0);
                assertThat(balance.subtract(held).signum()).as("seed %d step %d available >= 0", seed, step)
                        .isGreaterThanOrEqualTo(0);
                assertThat(nonHoldSum).as("seed %d step %d sum of non-hold amounts", seed, step)
                        .isEqualByComparingTo(balance);
                assertThat(openHolds.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                        .as("seed %d step %d sum of open holds", seed, step).isEqualByComparingTo(held);
            }
        }
    }
}
