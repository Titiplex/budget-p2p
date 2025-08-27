package com.titiplex.budget.core.store;

import com.titiplex.budget.core.crdt.HLC;
import com.titiplex.budget.core.model.*;
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
            st.executeUpdate("CREATE TABLE IF NOT EXISTS recurring (" +
                    "id TEXT PRIMARY KEY," +
                    "name TEXT," +
                    "period TEXT," +
                    "day INTEGER," +
                    "weekday INTEGER," +
                    "month INTEGER," +
                    "amount TEXT," +
                    "currency TEXT," +
                    "category TEXT," +
                    "note TEXT," +
                    "active INTEGER DEFAULT 1," +
                    "deleted INTEGER DEFAULT 0," +
                    "ver TEXT," +
                    "author TEXT" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS goals (" +
                    "id TEXT PRIMARY KEY, " +
                    "name TEXT, " +
                    "target TEXT, " +
                    "currency TEXT, " +
                    "due_ts INTEGER," +
                    "deleted INTEGER DEFAULT 0, " +
                    "ver TEXT, " +
                    "author TEXT)");

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS category(
                      id TEXT PRIMARY KEY,
                      name TEXT NOT NULL UNIQUE,     -- unicité par nom (insensible à la casse si tu veux: COLLATE NOCASE)
                      deleted INTEGER NOT NULL DEFAULT 0,
                      ver TEXT NOT NULL,
                      author TEXT NOT NULL
                    );
                    """);
            try {
                st.executeUpdate("ALTER TABLE budgets ADD COLUMN rollover_mode TEXT DEFAULT 'NONE'");
            } catch (SQLException ignore) {
            }
            try {
                st.executeUpdate("ALTER TABLE budgets ADD COLUMN rollover_cap TEXT DEFAULT '0'");
            } catch (SQLException ignore) {
            }
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
                            rs.getString("author"),
                            rs.getString("rollover_mode"),
                            new BigDecimal(rs.getString("rollover_cap"))
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

    @Override
    public void upsertRecurring(RecurringRule r) {
        try (PreparedStatement psSel = conn.prepareStatement("SELECT ver,author FROM recurring WHERE id=?")) {
            psSel.setString(1, r.id());
            try (ResultSet rs = psSel.executeQuery()) {
                boolean shouldWrite = true;
                if (rs.next())
                    shouldWrite = compareVer(r.ver(), rs.getString("ver"), r.author(), rs.getString("author")) > 0;
                if (shouldWrite) {
                    String sql = "INSERT INTO recurring(id,name,period,day,weekday,month,amount,currency,category,note,active,deleted,ver,author) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                            "ON CONFLICT(id) DO UPDATE SET name=excluded.name, period=excluded.period, day=excluded.day, weekday=excluded.weekday," +
                            "month=excluded.month, amount=excluded.amount, currency=excluded.currency, category=excluded.category, note=excluded.note," +
                            "active=excluded.active, deleted=excluded.deleted, ver=excluded.ver, author=excluded.author";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, r.id());
                        ps.setString(2, r.name());
                        ps.setString(3, r.period());
                        ps.setInt(4, r.day());
                        ps.setInt(5, r.weekday());
                        ps.setInt(6, r.month());
                        ps.setString(7, r.amount().toPlainString());
                        ps.setString(8, r.currency());
                        ps.setString(9, r.category());
                        ps.setString(10, r.note());
                        ps.setInt(11, r.active() ? 1 : 0);
                        ps.setInt(12, r.deleted() ? 1 : 0);
                        ps.setString(13, r.ver());
                        ps.setString(14, r.author());
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void tombstoneRecurring(String id, String ver, String author) {
        try (PreparedStatement psSel = conn.prepareStatement("SELECT ver,author FROM recurring WHERE id=?")) {
            psSel.setString(1, id);
            try (ResultSet rs = psSel.executeQuery()) {
                boolean shouldWrite = true;
                if (rs.next()) shouldWrite = compareVer(ver, rs.getString("ver"), author, rs.getString("author")) > 0;
                if (shouldWrite) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE recurring SET deleted=1, ver=?, author=? WHERE id=?")) {
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
    public List<RecurringRule> listRecurringActive() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM recurring WHERE deleted=0 AND active=1 ORDER BY name ASC")) {
            List<RecurringRule> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new RecurringRule(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("period"),
                            rs.getInt("day"),
                            rs.getInt("weekday"),
                            rs.getInt("month"),
                            new BigDecimal(rs.getString("amount")),
                            rs.getString("currency"),
                            rs.getString("category"),
                            rs.getString("note"),
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

    @Override
    public void upsertGoal(Goal g) {
        try (PreparedStatement psG = conn.prepareStatement("SELECT ver,author FROM goal WHERE id=?")) {
            psG.setString(1, g.id());
            try (ResultSet rs = psG.executeQuery()) {
                boolean shouldWrite = true;
                if (rs.next())
                    shouldWrite = compareVer(g.ver(), rs.getString("ver"), g.author(), rs.getString("author")) > 0;
                if (shouldWrite) {
                    String sql = "INSERT INTO goal(id,name,target,currency,due_ts,deleted,ver,author) " +
                            "VALUES(?,?,?,?,?,?,?,?) " +
                            "ON CONFLICT(id) DO UPDATE SET name=excluded.name, target=excluded.target, currency=excluded.currency, due_ts=excluded.due_ts," +
                            "deleted=excluded.deleted, ver=excluded.ver, author=excluded.author";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, g.id());
                        ps.setString(2, g.name());
                        ps.setString(3, g.currency());
                        ps.setLong(4, g.dueTs());
                        ps.setInt(5, g.deleted() ? 1 : 0);
                        ps.setString(6, g.ver());
                        ps.setString(7, g.author());
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void tombstoneGoal(String id, String ver, String author) {
        try (PreparedStatement psG = conn.prepareStatement("SELECT ver,author FROM goal WHERE id=?")) {
            psG.setString(1, id);
            try (ResultSet rs = psG.executeQuery()) {
                boolean shouldWrite = true;
                if (rs.next()) shouldWrite = compareVer(ver, rs.getString("ver"), author, rs.getString("author")) > 0;
                if (shouldWrite) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE goal SET deleted=1, ver=?, author=? WHERE id=?")) {
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
    public List<Goal> listGoalsActive() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM goal WHERE deleted=0 AND active=1 ORDER BY name ASC")) {
            List<Goal> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Goal(
                            rs.getString("id"),
                            rs.getString("name"),
                            new BigDecimal(rs.getString("target")),
                            rs.getString("currency"),
                            rs.getLong("due_ts"),
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

    @Override
    public List<Category> listCategoriesActive() {
        var out = new java.util.ArrayList<Category>();
        try (var ps = conn.prepareStatement("SELECT id,name,deleted,ver,author FROM category WHERE deleted=0 ORDER BY name ASC")) {
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Category(
                            rs.getString(1), rs.getString(2),
                            rs.getInt(3) != 0, rs.getString(4), rs.getString(5)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    @Override
    public void upsertCategory(Category c) {
        // Upsert par nom (UNIQUE) : si le nom existe, on "réactive" et on met à jour ver/author
        final String sql = """
                INSERT INTO category(id,name,deleted,ver,author)
                VALUES(?,?,?,?,?)
                ON CONFLICT(name) DO UPDATE SET deleted=excluded.deleted, ver=excluded.ver, author=excluded.author
                """;
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.id());
            ps.setString(2, c.name());
            ps.setInt(3, c.deleted() ? 1 : 0);
            ps.setString(4, c.ver());
            ps.setString(5, c.author());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void tombstoneCategory(String id, String ver, String author) {
        // on peut supprimer par id ou par nom unique ; ici par id
        try (var ps = conn.prepareStatement("UPDATE category SET deleted=1, ver=?, author=? WHERE id=?")) {
            ps.setString(1, ver);
            ps.setString(2, author);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}