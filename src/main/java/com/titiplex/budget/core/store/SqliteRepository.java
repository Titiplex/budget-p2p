
package com.titiplex.budget.core.store;

import com.titiplex.budget.core.model.Expense;
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
                    "deleted INTEGER DEFAULT 0" +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void saveExpense(Expense e) {
        String sql = "INSERT INTO expenses(id,who,category,amount,currency,note,ts,deleted) " +
                "VALUES(?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET who=excluded.who, category=excluded.category, amount=excluded.amount, " +
                "currency=excluded.currency, note=excluded.note, ts=excluded.ts, deleted=excluded.deleted";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, e.id());
            ps.setString(2, e.who());
            ps.setString(3, e.category());
            ps.setString(4, e.amount().toPlainString());
            ps.setString(5, e.currency());
            ps.setString(6, e.note());
            ps.setLong(7, e.ts());
            ps.setInt(8, e.deleted() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void deleteExpense(String id) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE expenses SET deleted=1 WHERE id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Expense findById(String id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM expenses WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Expense> listExpenses() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM expenses WHERE deleted=0 ORDER BY ts DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<Expense> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Expense map(ResultSet rs) throws SQLException {
        return new Expense(
                rs.getString("id"),
                rs.getString("who"),
                rs.getString("category"),
                new BigDecimal(rs.getString("amount")),
                rs.getString("currency"),
                rs.getString("note"),
                rs.getLong("ts"),
                rs.getInt("deleted") == 1
        );
    }
}
