/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.mirth.connect.client.core.ControllerException;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ExtensionController;
import com.mirth.connect.server.util.SqlConfig;

/** Checked transactions on this plugin's existing configuration row. No host changes. */
final class EngineRegistryPersistence implements RegistryStore.Persistence {
    private static final Logger logger = LogManager.getLogger(EngineRegistryPersistence.class);
    private static final String WHERE = " WHERE CATEGORY = ? AND NAME = ?";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    @FunctionalInterface interface Connections { Connection open() throws SQLException; }
    private record Row(boolean present, String value) {}
    private final Connections connections;
    private final String selectLocked;
    private final Supplier<RegistryStore> registry;

    EngineRegistryPersistence(ConfigurationController configuration, ExtensionController ignored, Supplier<RegistryStore> registry) {
        this(() -> SqlConfig.getInstance().getSqlSessionManager().getConfiguration().getEnvironment().getDataSource().getConnection(),
                database(configuration), registry);
    }

    EngineRegistryPersistence(Connections connections, String database, Supplier<RegistryStore> registry) {
        this.connections = Objects.requireNonNull(connections);
        this.registry = Objects.requireNonNull(registry);
        selectLocked = switch (database) {
            case "derby", "mysql", "oracle", "postgres" -> "SELECT VALUE FROM CONFIGURATION" + WHERE + " FOR UPDATE";
            case "sqlserver" -> "SELECT VALUE FROM CONFIGURATION WITH (UPDLOCK, HOLDLOCK)" + WHERE;
            default -> throw new IllegalArgumentException("Unsupported Python registry database: " + database);
        };
    }

    private static String database(ConfigurationController configuration) {
        try { return configuration.getDatabaseSettings().getDatabase(); }
        catch (ControllerException failure) { throw new IllegalStateException("Cannot read the engine database configuration", failure); }
    }

    /** The servlet already enforces permission and records the actor in its mutation receipt. */
    <T> T asActor(int ignored, Supplier<T> action) { return action.get(); }

    @Override public String read() throws IOException {
        Connection connection = null;
        try {
            connection = connections.open();
            Row row = readRow(connection, "SELECT VALUE FROM CONFIGURATION" + WHERE);
            if (row.present && row.value == null) throw new IOException("The Python registry configuration row contains null");
            if (row.value != null && row.value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
                throw new IOException("The Python registry exceeds 16 MiB");
            return row.value;
        } catch (SQLException failure) {
            throw new IOException("Cannot read the Python registry (SQL state " + failure.getSQLState() + ")", failure);
        } finally { close(connection); }
    }

    @Override public RegistryStore.WriteOutcome write(String expected, String next, String operationId) throws Exception {
        validate(next);
        Connection connection = null;
        boolean committed = false;
        boolean commitAttempted = false;
        boolean transactionEnded = false;
        Integer previousIsolation = null;
        Boolean previousAutoCommit = null;
        try {
            connection = connections.open();
            previousIsolation = connection.getTransactionIsolation();
            previousAutoCommit = connection.getAutoCommit();
            // Derby's READ_COMMITTED cursor lock can end when the ResultSet
            // closes. Retain predecessor/range locks until transaction end,
            // including when the plugin-owned row is initially absent.
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            Row row = readRow(connection, selectLocked);
            if (row.present != (expected != null) || !Objects.equals(row.value, expected)) {
                connection.rollback();
                transactionEnded = true;
                return RegistryStore.WriteOutcome.CONFLICT;
            }
            if (!Objects.equals(row.value, next)) {
                String sql = row.present ? "UPDATE CONFIGURATION SET VALUE = ?" + WHERE
                        : "INSERT INTO CONFIGURATION (VALUE, CATEGORY, NAME) VALUES (?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(5);
                    statement.setString(1, next);
                    statement.setString(2, PythonContract.EXTENSION_NAME);
                    statement.setString(3, RegistryStore.PROPERTY_KEY);
                    if (statement.executeUpdate() != 1) throw new SQLException("Unexpected Python registry row count");
                }
            }
            commitAttempted = true;
            connection.commit();
            committed = true;
            transactionEnded = true;
            return RegistryStore.WriteOutcome.COMMITTED;
        } catch (SQLException failure) {
            if (commitAttempted) return RegistryStore.WriteOutcome.OUTCOME_UNKNOWN;
            if (isConflict(failure)) return RegistryStore.WriteOutcome.CONFLICT;
            throw new IOException("Cannot write the Python registry (SQL state " + failure.getSQLState() + ")", failure);
        } finally {
            if (connection != null && !committed) {
                try { connection.rollback(); transactionEnded = true; }
                catch (SQLException failure) { logger.warn("Python registry transaction rollback could not be confirmed (SQL state " + failure.getSQLState() + ")"); }
            }
            // Restore healthy pooled connection state only after a confirmed
            // transaction end. Do not change it while rollback is uncertain.
            if (connection != null && transactionEnded && previousIsolation != null) {
                try { connection.setTransactionIsolation(previousIsolation); }
                catch (SQLException failure) { logger.warn("Python registry isolation reset failed (SQL state " + failure.getSQLState() + ")"); }
            }
            if (connection != null && transactionEnded && previousAutoCommit != null) {
                try { connection.setAutoCommit(previousAutoCommit); }
                catch (SQLException failure) { logger.warn("Python registry auto-commit reset failed (SQL state " + failure.getSQLState() + ")"); }
            }
            close(connection);
        }
    }

    /**
     * Stock engine configuration writes have already committed before this callback.
     * Valid administrative replacements get a fresh epoch through an exact CAS.
     * Whole server restores must quiesce plugin API activity; the stock engine does
     * not provide a pre-write validation hook for a plugin to protect this boundary.
     */
    void update(Properties incoming) throws ControllerException {
        if (incoming == null || incoming.size() != 1 || incoming.getProperty(RegistryStore.PROPERTY_KEY) == null)
            throw new ControllerException("Python Runtime requires one configuration document; use profile import/export");
        RegistryStore store = registry.get();
        if (store == null) throw new ControllerException("Python Runtime registry is not initialized");
        String json = incoming.getProperty(RegistryStore.PROPERTY_KEY);
        synchronized (store) {
            try {
                String replacement = store.prepareReplacement(json);
                RegistryStore.WriteOutcome result = write(json, replacement, "configuration-replacement");
                if (result != RegistryStore.WriteOutcome.COMMITTED) {
                    String observed = read();
                    if (!replacement.equals(observed)) throw new IOException("Configuration changed during replacement; quiesce plugin activity and restore again");
                }
                store.adoptCommitted(replacement);
            } catch (Exception failure) {
                throw new ControllerException("Python registry replacement failed; use profile import/export or a quiesced server restore", failure);
            }
        }
    }

    static Properties properties(String json) {
        Properties result = new Properties(); result.setProperty(RegistryStore.PROPERTY_KEY, json); return result;
    }

    private void validate(String json) throws IOException {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Python registry exceeds 16 MiB");
        RegistryStore store = registry.get();
        if (store != null) { store.parse(json); return; }
        PythonApi.Registry value = PythonApi.mapper().readValue(json, PythonApi.Registry.class);
        if (value == null || value.schemaVersion != PythonApi.REGISTRY_SCHEMA_VERSION || value.registryEpoch == null || value.profiles == null)
            throw new IOException("Invalid Python registry document");
    }

    private static Row readRow(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setString(1, PythonContract.EXTENSION_NAME);
            statement.setString(2, RegistryStore.PROPERTY_KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return new Row(false, null);
                String value = result.getString(1);
                if (result.next()) throw new SQLException("Duplicate Python registry configuration rows");
                return new Row(true, value);
            }
        }
    }

    private static boolean isConflict(SQLException failure) {
        String state = failure.getSQLState(); int code = failure.getErrorCode();
        return "23505".equals(state) || state != null && state.startsWith("40")
                || code == 1 || code == 1062 || code == 1205 || code == 1213 || code == 2601 || code == 2627 || code == 8177;
    }

    private static void close(Connection connection) {
        if (connection != null) try { connection.close(); }
        catch (SQLException failure) { logger.warn("Python registry connection close failed (SQL state " + failure.getSQLState() + ")"); }
    }
}
