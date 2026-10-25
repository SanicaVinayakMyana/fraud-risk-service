package com.sanica.risk.support

import com.sanica.risk.domain.Channel
import com.sanica.risk.domain.TransactionEvent
import java.time.Instant

/** Transaction events with sensible defaults; override any field by name. */
class Events {

    static final Instant T0 = Instant.parse('2026-10-25T12:00:00Z')

    static TransactionEvent tx(Map o = [:]) {
        new TransactionEvent(
                o.containsKey('id') ? o.id : UUID.randomUUID().toString(),
                o.containsKey('account') ? o.account : 'acct-1',
                o.containsKey('counterparty') ? o.counterparty : 'payee-1',
                new BigDecimal((o.containsKey('amount') ? o.amount : '25.00').toString()),
                o.containsKey('currency') ? o.currency : 'USD',
                o.containsKey('channel') ? o.channel : Channel.CARD,
                o.containsKey('device') ? o.device : 'dev-1',
                o.containsKey('country') ? o.country : 'US',
                o.containsKey('at') ? o.at : T0)
    }

    /** {@code count} ordinary payments, one every 10 minutes ending an hour before {@code before}. */
    static List<TransactionEvent> history(String account, int count, Instant before = T0, String amount = '25.00') {
        (0..<count).collect { i ->
            tx(account: account, amount: amount, at: before.minusSeconds(3_600 + (count - i) * 600L))
        }
    }
}
