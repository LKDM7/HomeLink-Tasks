package fr.lkdm.homelink.tasks.server;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestLedgerTest {
    @Test void replayDoesNotSpendQuotaOrMutateAgain() {
        var ledger = new RequestLedger(); var player = UUID.randomUUID(); var op = UUID.randomUUID();
        assertEquals(RequestLedger.Result.ACCEPTED, ledger.claim(player, op, 10));
        assertEquals(RequestLedger.Result.DUPLICATE, ledger.claim(player, op, 11));
    }
    @Test void quotaIsPerPlayerAndRecoversAfterWindow() {
        var ledger = new RequestLedger(); var player = UUID.randomUUID();
        for (int i = 0; i < 32; i++) assertEquals(RequestLedger.Result.ACCEPTED, ledger.claim(player, UUID.randomUUID(), 10));
        assertEquals(RequestLedger.Result.THROTTLED, ledger.claim(player, UUID.randomUUID(), 10));
        assertEquals(RequestLedger.Result.ACCEPTED, ledger.claim(UUID.randomUUID(), UUID.randomUUID(), 10));
        assertEquals(RequestLedger.Result.ACCEPTED, ledger.claim(player, UUID.randomUUID(), 30));
    }
    @Test void logoutResetsConnectionLedger() {
        var ledger = new RequestLedger(); var player = UUID.randomUUID(); var op = UUID.randomUUID();
        ledger.claim(player, op, 10); ledger.forget(player);
        assertEquals(RequestLedger.Result.ACCEPTED, ledger.claim(player, op, 11));
    }
}
