package com.titiplex.budget.core.store;

import com.titiplex.budget.core.crdt.HLC;
import com.titiplex.budget.core.model.CategoryBudget;
import com.titiplex.budget.core.model.Expense;
import com.titiplex.budget.core.model.FxRate;
import com.titiplex.budget.core.model.Rule;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Repository
public class SqliteRepository implements com.titiplex.budget.core.store.Repository {

    private final Connection conn;

    public SqliteRepository() {
        try {
            String dir = System.getProperty("user.home") + "/.budget-p2p";
            Files.createDirectories(Path.of(dir));
            conn = DriverManager.getConnection("jdbc:sqlite:" + dir + "/db.sqlite");
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void init() {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS expenses (" +
                    "id TEXT PRIMARY KEY," +
                    "who TEXT," +
                    "category TEXT," +
                    "amount TEXT," +
                    "currency TEXT," +
                    "note TEXT," +
                    "ts INTEGER," +
                    "deleted INTEGER DEFAULT 0," +
                    "ver TEXT," +
                    "author TEXT" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS budgets (" +
                    "id TEXT PRIMARY KEY," +
                    "category TEXT UNIQUE," +
                    "monthly_limit TEXT," +
                    "currency TEXT," +
                    "deleted INTEGER DEFAULT 0," +
                    "ver TEXT," +
                    "author TEXT" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS fx_rates (" +
                    "code TEXT PRIMARY KEY," +
                    "per_base TEXT," +
                    "deleted INTEGER DEFAULT 0," +
                    "ver TEXT," +
                    "author TEXT" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS rules (" +
                    "id TEXT PRIMARY KEY," +
                    "name TEXT," +
                    "kind TEXT," +
                    "pattern TEXT," +
                    "category TEXT," +
                    "active INTEGER DEFAULT 1," +
                    "deleted INTEGER DEFAULT 0," +
                    "ver TEXT," +
                    "author TEXT" +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- Expenses ----------
    @Override
    public void upsertExpense(Expense e) {
        Expense cur = findById(e.id());
        if (cur == null || compareVer(e.ver(), cur.ver(), e.author(), cur.author()) > 0) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO expenses(id,who,category,amount,currency,note,ts,deleted,ver,author) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?) " +
                            "ON CONFLICT(id) DO UPDATE SET who=excluded.who, category=excluded.category, amount=excluded.amount," +
                            "currency=excluded.currency, note=excluded.note, ts=excluded.ts, deleted=excluded.deleted, ver=excluded.ver, author=excluded.author")) {
                ps.setString(1, e.id());
                ps.setString(2, e.who());
                ps.setString(3, e.category());
                ps.setString(4, e.amount().toPlainString());
                ps.setString(5, e.currency());
                ps.setString(6, e.note());
                ps.setLong(7, e.ts());
                ps.setInt(8, e.deleted() ? 1 : 0);
                ps.setString(9, e.ver());
                ps.setString(10, e.author());
                ps.executeUpdate();
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    @Override
    public void tombstone(String id, String ver, String author) {
        Expense cur = findById(id);
        if (cur == null || compareVer(ver, cur.ver(), author, cur.author()) > 0) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE expenses SET deleted=1, ver=?, author=? WHERE id=?")) {
                ps.setString(1, ver);
                ps.setString(2, author);
                ps.setString(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public Expense findById(String id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM expenses WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapExpense(rs);
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Expense> listActive() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM expenses WHERE deleted=0 ORDER BY ts DESC")) {
            List<Expense> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapExpense(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Expense mapExpense(ResultSet rs) throws SQLException {
        return new Expense(
                rs.getString("id"),
                rs.getString("who"),
                rs.getString("category"),
                new BigDecimal(rs.getString("amount")),
                rs.getString("currency"),
                rs.getString("note"),
                rs.getLong("ts"),
                rs.getInt("deleted") == 1,
                rs.getString("ver"),
                rs.getString("author")
        );
    }

    private int compareVer(String a, String b, String authorA, String authorB) {
        if (b == null) return 1;
        HLC ha = HLC.parse(a);
        HLC hb = HLC.parse(b);
        int c = ha.compareTo(hb);
        if (c != 0) return c;
        return authorA.compareTo(authorB);
    }

    // ---------- Budgets ----------
    @Override
    public void upsertBudget(CategoryBudget b) {
        try (PreparedStatement psSel = conn.prepareStatement("SELECT ver,author FROM budgets WHERE category=?")) {
            psSel.setString(1, b.category());
            try (ResultSet rs = psSel.executeQuery()) {
                boolean shouldWrite = true;
                if (rs.next())
                    shouldWrite = compareVer(b.ver(), rs.getString("ver"), b.author(), rs.getString("author")) > 0;
                if (shouldWrite) {
                    String sql = "INSERT INTO budgets(id,category,monthly_limit,currency,deleted,ver,author) " +
                            "VALUES(?,?,?,?,?,?,?) " +
                            "ON CONFLICT(category) DO UPDATE SET monthly_limit=excluded.monthly_limit, currency=excluded.currency, " +
                            "deleted=excluded.deleted, ver=excluded.ver, author=excluded.author";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, b.id());
                        ps.setString(2, b.category());
                        ps.setString(3, b.monthlyLimit().toPlainString());
                        ps.setString(4, b.currency());
                        ps.setInt(5, b.deleted() ? 1 : 0);
                        ps.setString(6, b.ver());
                        ps.setString(7, b.author());
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void tombstoneBudget(String category, String ver, String author) {
        try (PreparedStatement psSel = conn.prepareStatement("SELECT ver,author FROM budgets WHERE category=?")) {
            psSel.setString(1, category);
            try (ResultSet rs = psSel.executeQuery()) {
                boolean shouldWrite = true;
                if (rs.next()) shouldWrite = compareVer(ver, rs.getString("ver"), author, rs.getString("author")) > 0;
                if (shouldWrite) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE budgets SET deleted=1, ver=?, author=? WHERE category=?")) {
                        ps.setString(1, ver);
                        ps.setString(2, author);
                        ps.setString(3, category);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<CategoryBudget> listBudgetsActive() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM budgets WHERE deleted=0 ORDER BY category ASC")) {
            List<CategoryBudget> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CategoryBudget(
                            rs.getString("id"),
                            rs.getString("category"),
                            new BigDecimal(rs.getString("monthly_limit")),
                            rs.getString("currency"),
                            false,
                            rs.getString("ver"),
                            rs.getString("author")
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- FX ----------
    @Override
    public void upsertFx(FxRate r) {
        try (PreparedStatement psSel = conn.prepareStatement("SELECT ver,author FROM fx_rates WHERE code=?")) {
            psSel.setString(1, r.code());
            try (ResultSet rs = psSel.executeQuery()) {
                boolean shouldWrite = true;
                if (rs.next())
                    shouldWrite = compareVer(r.ver(), rs.getString("ver"), r.author(), rs.getString("author")) > 0;
                if (shouldWrite) {
                    String sql = "INSERT INTO fx_rates(code,per_base,deleted,ver,author) VALUES(?,?,?,?,?) " +
                            "ON CONFLICT(code) DO UPDATE SET per_base=excluded.per_base, deleted=excluded.deleted, ver=excluded.ver, author=excluded.author";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, r.code());
                        ps.setString(2, r.perBase().toPlainString());
                        ps.setInt(3, r.deleted() ? 1 : 0);
                        ps.setString(4, r.ver());
                        ps.setString(5, r.author());
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void tombstoneFx(String code, String ver, String author) {
        try (PreparedStatement psSel = conn.prepareStatement("SELECT ver,author FROM fx_rates WHERE code=?")) {
            psSel.setString(1, code);
            try (ResultSet rs = psSel.executeQuery()) {
                boolean shouldWrite = true;
                if (rs.next()) shouldWrite = compareVer(ver, rs.getString("ver"), author, rs.getString("author")) > 0;
                if (shouldWrite) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE fx_rates SET deleted=1, ver=?, author=? WHERE code=?")) {
                        ps.setString(1, ver);
                        ps.setString(2, author);
                        ps.setString(3, code);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<FxRate> listFxActive() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM fx_rates WHERE deleted=0 ORDER BY code ASC")) {
            List<FxRate> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new FxRate(
                            rs.getString("code"),
                            new BigDecimal(rs.getString("per_base")),
                            false,
                            rs.getString("ver"),
                            rs.getString("author")
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- Rules ----------
    @Override
    public void upsertRule(Rule r) {
        try (PreparedStatement psSel = conn.prepareStatement("SELECT ver,author FROM rules WHERE id=?")) {
            psSel.setString(1, r.id());
            try (ResultSet rs = psSel.executeQuery()) {
                boolean shouldWrite = true;
                if (rs.next())
                    shouldWrite = compareVer(r.ver(), rs.getString("ver"), r.author(), rs.getString("author")) > 0;
                if (shouldWrite) {
                    String sql = "INSERT INTO rules(id,name,kind,pattern,category,active,deleted,ver,author) " +
                            "VALUES(?,?,?,?,?,?,?,?,?) " +
                            "ON CONFLICT(id) DO UPDATE SET name=excluded.name, kind=excluded.kind, pattern=excluded.pattern, " +
                            "category=excluded.category, active=excluded.active, deleted=excluded.deleted, ver=excluded.ver, author=excluded.author";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, r.id());
                        ps.setString(2, r.name());
                        ps.setString(3, r.kind());
                        ps.setString(4, r.pattern());
                        ps.setString(5, r.category());
                        ps.setInt(6, r.active() ? 1 : 0);
                        ps.setInt(7, r.deleted() ? 1 : 0);
                        ps.setString(8, r.ver());
                        ps.setString(9, r.author());
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void tombstoneRule(String id, String ver, String author) {
        try (PreparedStatement psSel = conn.prepareStatement("SELECT ver,author FROM rules WHERE id=?")) {
            psSel.setString(1, id);
            try (ResultSet rs = psSel.executeQuery()) {
                boolean shouldWrite = true;
                if (rs.next()) shouldWrite = compareVer(ver, rs.getString("ver"), author, rs.getString("author")) > 0;
                if (shouldWrite) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE rules SET deleted=1, ver=?, author=? WHERE id=?")) {
                        ps.setString(1, ver);
                        ps.setString(2, author);
                        ps.setString(3, id);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Rule> listRulesActive() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM rules WHERE deleted=0 AND active=1 ORDER BY name ASC")) {
            List<Rule> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Rule(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("kind"),
                            rs.getString("pattern"),
                            rs.getString("category"),
                            rs.getInt("active") == 1,
                            false,
                            rs.getString("ver"),
                            rs.getString("author")
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}