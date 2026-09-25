package pl.kudlacze.core.currency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.kudlacze.core.TestDb;
import pl.kudlacze.core.db.Sql;
import pl.kudlacze.core.util.Tasks;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KcServiceTest {

    private KcService kc;
    private KcRepository repo;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Sql sql = TestDb.fresh();
        repo = new KcRepository(sql);
        kc = new KcService(repo, Tasks.direct(Logger.getLogger("test")),
                Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC), "survival");
    }

    @Test
    void newPlayerHasZeroBalance() {
        assertEquals(0L, kc.balance(alice).join());
    }

    @Test
    void giveAndTakeUpdateBalanceAndLog() {
        assertEquals(10L, kc.give(alice, 10, KcService.TxType.QUEST, "quest drewno").join());
        assertEquals(7L, kc.take(alice, 3, KcService.TxType.ZAKUP_RANGI, "test").join());
        assertEquals(7L, kc.cachedBalance(alice));
        List<KcRepository.Transaction> history = kc.history(alice, 10).join();
        assertEquals(2, history.size());
        assertEquals(-3, history.getFirst().change());
        assertEquals(7, history.getFirst().balanceAfter());
        assertEquals("ZAKUP_RANGI", history.getFirst().type());
        assertEquals("survival", history.getFirst().server());
    }

    @Test
    void takingMoreThanBalanceFailsWithoutChanges() {
        kc.give(alice, 5, KcService.TxType.QUEST, "q").join();
        CompletionException ex = assertThrows(CompletionException.class,
                () -> kc.take(alice, 6, KcService.TxType.ZAKUP_RANGI, "r").join());
        KcService.InsufficientKcException cause = assertInstanceOf(KcService.InsufficientKcException.class, ex.getCause());
        assertEquals(5, cause.balance());
        assertEquals(5L, kc.balance(alice).join());
        assertEquals(1, kc.history(alice, 10).join().size());
    }

    @Test
    void amountsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> kc.give(alice, 0, KcService.TxType.QUEST, "x"));
        assertThrows(IllegalArgumentException.class, () -> kc.take(alice, -1, KcService.TxType.QUEST, "x"));
    }

    @Test
    void transferMovesFundsAtomically() {
        kc.give(alice, 20, KcService.TxType.QUEST, "q").join();
        kc.transfer(alice, bob, 15).join();
        assertEquals(5L, kc.balance(alice).join());
        assertEquals(15L, kc.balance(bob).join());
        assertThrows(CompletionException.class, () -> kc.transfer(alice, bob, 6).join());
        assertEquals(5L, kc.balance(alice).join());
        assertEquals(15L, kc.balance(bob).join());
    }

    @Test
    void cannotTransferToSelf() {
        assertThrows(IllegalArgumentException.class, () -> kc.transfer(alice, alice, 1));
    }

    @Test
    void adminSetOverridesBalance() {
        kc.give(alice, 3, KcService.TxType.QUEST, "q").join();
        assertEquals(100L, kc.set(alice, 100, "admin:test").join());
        assertEquals(97, kc.history(alice, 1).join().getFirst().change());
    }

    @Test
    void withdrawCreatesBatchAndDepositCanRedeemItOnce() {
        kc.give(alice, 30, KcService.TxType.QUEST, "q").join();
        UUID batch = kc.withdraw(alice, 20).join();
        assertEquals(10L, kc.balance(alice).join());
        assertEquals(20L, repo.batchRemaining(batch).orElseThrow());

        // bob dostał od alice przedmioty i wpłaca je w dwóch częściach (stos podzielony na 12 + 8)
        assertEquals(12L, kc.deposit(bob, batch, 12).join().orElseThrow().balanceAfter());
        assertEquals(20L, kc.deposit(bob, batch, 8).join().orElseThrow().balanceAfter());
        assertEquals(0L, repo.batchRemaining(batch).orElseThrow());

        // zduplikowany przedmiot z tej samej partii — wpłata odrzucona, saldo bez zmian
        assertTrue(kc.deposit(bob, batch, 5).join().isEmpty());
        assertEquals(20L, kc.balance(bob).join());
    }

    @Test
    void depositCreditsOnlyWhatIsLeftInBatch() {
        kc.give(alice, 10, KcService.TxType.QUEST, "q").join();
        UUID batch = kc.withdraw(alice, 10).join();
        // gracz ma 20 przedmiotów z partii 10 (10 zduplikowanych) — uznane tylko 10
        KcRepository.Deposit d = kc.deposit(alice, batch, 20).join().orElseThrow();
        assertEquals(10, d.credited());
        assertEquals(10, d.balanceAfter());
    }

    @Test
    void depositOfUnknownBatchIsRejected() {
        assertFalse(kc.deposit(alice, UUID.randomUUID(), 3).join().isPresent());
        assertEquals(0L, kc.balance(alice).join());
    }

    @Test
    void withdrawWithoutFundsFails() {
        kc.give(alice, 2, KcService.TxType.QUEST, "q").join();
        assertThrows(CompletionException.class, () -> kc.withdraw(alice, 3).join());
        assertEquals(2L, kc.balance(alice).join());
    }
}
