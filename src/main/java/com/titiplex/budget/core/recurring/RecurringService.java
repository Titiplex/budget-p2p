package com.titiplex.budget.core.recurring;

import com.titiplex.budget.core.crdt.HLC;
import com.titiplex.budget.core.crypto.SessionState;
import com.titiplex.budget.core.model.Expense;
import com.titiplex.budget.core.model.Op;
import com.titiplex.budget.core.model.RecurringRule;
import com.titiplex.budget.core.p2p.P2PService;
import com.titiplex.budget.core.store.Repository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class RecurringService {
    private final Repository repo;
    private final P2PService p2p;
    private final SessionState ss;
    private final HLC.Clock clock;
    private ScheduledExecutorService ses;

    public RecurringService(Repository repo, P2PService p2p, SessionState ss) {
        this.repo = repo;
        this.p2p = p2p;
        this.ss = ss;
        this.clock = new HLC.Clock(ss.userId != null ? ss.userId : UUID.randomUUID().toString());
    }

    public void start() {
        if (ses != null) return;
        ses = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "recurring");
            t.setDaemon(true);
            return t;
        });
        // check toutes les heures (peu coûteux)
        ses.scheduleAtFixedRate(this::instantiateDue, 0, 1, TimeUnit.HOURS);
    }

    private void instantiateDue() {
        try {
            final long nowMs = System.currentTimeMillis();
            final LocalDate today = LocalDate.now();
            final DayOfWeek dow = today.getDayOfWeek();
            final int dom = today.getDayOfMonth();
            final int month = today.getMonthValue();

            List<RecurringRule> rules = repo.listRecurringActive();
            for (RecurringRule r : rules) {
                if (!isDue(r, today, dom, dow, month)) continue;

                String ver = clock.tick();
                Expense e = new Expense(
                        UUID.randomUUID().toString(),
                        ss.displayName != null ? ss.displayName : ss.userId,
                        r.category(),
                        r.amount() != null ? r.amount() : BigDecimal.ZERO,
                        r.currency(),
                        "[auto] " + r.name(),
                        nowMs,
                        false,
                        ver,
                        ss.userId
                );
                repo.upsertExpense(e);
                p2p.broadcast(new Op(Op.Type.ADD, e));
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isDue(RecurringRule r, LocalDate today, int dom, DayOfWeek dow, int month) {
        if (!r.active() || r.deleted()) return false;
        return switch (r.period()) {
            case "MONTHLY" -> r.day() == dom;
            case "WEEKLY" -> r.weekday() == dow.getValue();
            case "YEARLY" -> (r.month() == month && r.day() == dom);
            default -> false;
        };
    }
}