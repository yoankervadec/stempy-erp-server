package com.lesconstructionssapete.stempyerpserver.platform.testsupport;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Just enough JDBC to drive UnitOfWork + QueryExecutor without a database:
 * every query returns the canned rows (column name -> value), and the last
 * prepared SQL and its bound parameters are recorded for assertions.
 *
 * For tests of the web/query plumbing only. Anything that depends on real SQL
 * semantics needs a Testcontainers MySQL test.
 */
public final class FakeDataSource implements DataSource {

  private final List<Map<String, Object>> rows;
  private int connectionsOpened;
  private String lastSql;
  private final Map<Integer, Object> lastParams = new TreeMap<>();

  public FakeDataSource(List<Map<String, Object>> rows) {
    this.rows = rows;
  }

  public synchronized int connectionsOpened() {
    return connectionsOpened;
  }

  public synchronized String lastSql() {
    return lastSql;
  }

  /** Bound parameters of the last statement, in placeholder order. */
  public synchronized List<Object> lastParams() {
    return new ArrayList<>(lastParams.values());
  }

  @Override
  public synchronized Connection getConnection() {
    connectionsOpened++;
    return proxy(Connection.class, (p, m, args) -> switch (m.getName()) {
      case "prepareStatement" -> statement((String) args[0]);
      default -> defaultValue(m.getReturnType());
    });
  }

  private synchronized PreparedStatement statement(String sql) {
    lastSql = sql;
    lastParams.clear();
    return proxy(PreparedStatement.class, (p, m, args) -> {
      if (m.getName().startsWith("set") && args != null && args.length == 2 && args[0] instanceof Integer i) {
        synchronized (this) {
          lastParams.put(i, args[1]);
        }
        return null;
      }
      return switch (m.getName()) {
        case "executeQuery" -> resultSet();
        default -> defaultValue(m.getReturnType());
      };
    });
  }

  private ResultSet resultSet() {
    Iterator<Map<String, Object>> it = rows.iterator();
    Object[] state = new Object[2]; // [current row, last value read]
    return proxy(ResultSet.class, (p, m, args) -> {
      switch (m.getName()) {
        case "next" -> {
          state[0] = it.hasNext() ? it.next() : null;
          return state[0] != null;
        }
        case "wasNull" -> {
          return state[1] == null;
        }
        case "getString", "getLong", "getBigDecimal", "getBoolean", "getObject" -> {
          @SuppressWarnings("unchecked")
          Object v = ((Map<String, Object>) state[0]).get((String) args[0]);
          state[1] = v;
          return switch (m.getName()) {
            case "getString" -> v == null ? null : v.toString();
            case "getLong" -> v == null ? 0L : ((Number) v).longValue();
            case "getBigDecimal" -> v == null ? null : new BigDecimal(v.toString());
            case "getBoolean" -> v != null && (Boolean) v;
            default -> v;
          };
        }
        default -> {
          return defaultValue(m.getReturnType());
        }
      }
    });
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(FakeDataSource.class.getClassLoader(), new Class<?>[] { type }, handler));
  }

  private static Object defaultValue(Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }

  // ---- unused DataSource methods ----

  @Override
  public Connection getConnection(String username, String password) {
    return getConnection();
  }

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(PrintWriter out) {
  }

  @Override
  public void setLoginTimeout(int seconds) {
  }

  @Override
  public int getLoginTimeout() {
    return 0;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getGlobal();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new SQLException("not a wrapper");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return false;
  }
}
