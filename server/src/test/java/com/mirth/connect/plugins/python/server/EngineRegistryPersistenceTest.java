/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;

/** Private JDBC databases only; no engine process or shared database is touched. */
class EngineRegistryPersistenceTest {
    static String document() throws Exception {
        PythonApi.Registry value = new PythonApi.Registry(); value.registryEpoch = UUID.randomUUID().toString();
        return PythonApi.mapper().writeValueAsString(value);
    }

    static final class Database implements AutoCloseable {
        final Path root = Files.createTempDirectory("oie-python-registry-").toRealPath();
        final Path evidence = Path.of("target/runtime-evidence/registry-jdbc", UUID.randomUUID().toString()).toAbsolutePath();
        final String url = "jdbc:derby:" + root.resolve("database") + ";create=true";
        final EngineRegistryPersistence persistence = new EngineRegistryPersistence(this::connection, "derby", () -> null);
        Database() throws Exception {
            evidence.toFile().mkdirs();
            Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
            try (Connection connection = connection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE CONFIGURATION (CATEGORY VARCHAR(100) NOT NULL, NAME VARCHAR(100) NOT NULL, VALUE CLOB, PRIMARY KEY (CATEGORY, NAME))");
                statement.execute("INSERT INTO CONFIGURATION VALUES ('Unrelated Extension', 'configuration', 'preserve')");
            }
        }
        Connection connection() throws SQLException { return DriverManager.getConnection(url); }
        void external(String value) throws Exception {
            try (Connection connection = connection(); var statement = connection.prepareStatement("UPDATE CONFIGURATION SET VALUE=? WHERE CATEGORY=? AND NAME=?")) {
                statement.setString(1,value); statement.setString(2,PythonContract.EXTENSION_NAME); statement.setString(3,RegistryStore.PROPERTY_KEY);
                assertEquals(1,statement.executeUpdate());
            }
        }
        @Override public void close() throws Exception {
            var rows = new ArrayList<Map<String,Object>>();
            try (Connection connection = connection(); var statement = connection.createStatement(); var result=statement.executeQuery("SELECT CATEGORY,NAME,VALUE FROM CONFIGURATION ORDER BY CATEGORY,NAME")) {
                while(result.next()) { var row=new LinkedHashMap<String,Object>(); row.put("category",result.getString(1));row.put("name",result.getString(2));row.put("value",result.getString(3));rows.add(row); }
            }
            assertTrue(rows.stream().anyMatch(row -> "Unrelated Extension".equals(row.get("category")) && "preserve".equals(row.get("value"))));
            Files.writeString(evidence.resolve("final-rows.json"), PythonApi.mapper().writeValueAsString(rows));
            boolean closed=false;
            try { DriverManager.getConnection(url.replace(";create=true", ";shutdown=true")); }
            catch(SQLException shutdown) { closed="08006".equals(shutdown.getSQLState()); }
            assertTrue(closed,"Private Derby database did not confirm shutdown");
            Files.writeString(evidence.resolve("ownership.json"),PythonApi.mapper().writeValueAsString(Map.of(
                "databaseRoot",root.toString(),"testJvmPid",ProcessHandle.current().pid(),"databaseShutdownConfirmed",true,
                "sharedEngineTouched",false,"cleanupPendingJvmExit",true)));
        }
    }

    @Test void exactAbsentAndPresentCasCannotReplaceAnotherWriter() throws Exception {
        try(Database db=new Database()) {
            String first=document(), second=document();
            assertNull(db.persistence.read());
            assertEquals(RegistryStore.WriteOutcome.COMMITTED,db.persistence.write(null,first,"first"));
            assertEquals(RegistryStore.WriteOutcome.CONFLICT,db.persistence.write(null,second,"absent"));
            assertEquals(RegistryStore.WriteOutcome.CONFLICT,db.persistence.write(document(),second,"stale"));
            assertEquals(first,db.persistence.read());
            assertEquals(RegistryStore.WriteOutcome.COMMITTED,db.persistence.write(first,second,"second"));
            assertEquals(second,db.persistence.read());
        }
    }

    @Test void twoConcurrentSqlWritersHaveOneWinnerAndPreserveThePredecessor() throws Exception {
        try(Database db=new Database()) {
            String first=document(), a=document(), b=document();db.persistence.write(null,first,"seed");
            var workers=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
            try {
                var fa=workers.submit(()->{start.await();return db.persistence.write(first,a,"a");});
                var fb=workers.submit(()->{start.await();return db.persistence.write(first,b,"b");});start.countDown();
                var results=java.util.List.of(fa.get(10,java.util.concurrent.TimeUnit.SECONDS),fb.get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1,results.stream().filter(x->x==RegistryStore.WriteOutcome.COMMITTED).count());
                assertEquals(1,results.stream().filter(x->x==RegistryStore.WriteOutcome.CONFLICT).count());
                assertTrue(java.util.Set.of(a,b).contains(db.persistence.read()));
            } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }

    @Test void commitAcknowledgementLossIsUnknownAndCheckedReadFindsDurableBytes() throws Exception {
        try(Database db=new Database()) {
            String next=document();
            var uncertain=new EngineRegistryPersistence(()->{
                Connection delegate=db.connection();
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    try {Object result=method.invoke(delegate,args);if(method.getName().equals("commit"))throw new SQLException("acknowledgement lost","08006");return result;}
                    catch(InvocationTargetException failure){throw failure.getCause();}
                });
            },"derby",()->null);
            assertEquals(RegistryStore.WriteOutcome.OUTCOME_UNKNOWN,uncertain.write(null,next,"operation"));
            assertEquals(next,db.persistence.read());
        }
    }

    @Test void concurrentInitialInsertHasExactlyOneWinner() throws Exception {
        try (Database db = new Database()) {
            String a = document(), b = document();
            var workers = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var first = workers.submit(() -> { start.await(); return db.persistence.write(null, a, "a"); });
                var second = workers.submit(() -> { start.await(); return db.persistence.write(null, b, "b"); });
                start.countDown();
                var results = java.util.List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(value -> value == RegistryStore.WriteOutcome.COMMITTED).count());
                assertEquals(1, results.stream().filter(value -> value == RegistryStore.WriteOutcome.CONFLICT).count());
                assertTrue(java.util.Set.of(a, b).contains(db.persistence.read()));
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    @Test void completedTransactionsRestoreThePooledConnectionsIsolation() throws Exception {
        try (Database db = new Database()) {
            var resets = new java.util.concurrent.atomic.AtomicInteger();
            var autoCommitResets = new java.util.concurrent.atomic.AtomicInteger();
            var persistence = new EngineRegistryPersistence(() -> {
                Connection delegate = db.connection();
                int original = delegate.getTransactionIsolation();
                boolean originalAutoCommit = delegate.getAutoCommit();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        if (method.getName().equals("setTransactionIsolation") && (int) args[0] == original) resets.incrementAndGet();
                        if (method.getName().equals("setAutoCommit") && (boolean) args[0] == originalAutoCommit) autoCommitResets.incrementAndGet();
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }, "derby", () -> null);
            String value = document();
            assertEquals(RegistryStore.WriteOutcome.COMMITTED, persistence.write(null, value, "seed"));
            assertEquals(RegistryStore.WriteOutcome.CONFLICT, persistence.write(null, document(), "stale"));
            assertEquals(2, resets.get());
            assertEquals(2, autoCommitResets.get());
        }
    }

    @Test void invalidDocumentFailsBeforeDatabaseAccess() throws Exception {
        var accesses=new java.util.concurrent.atomic.AtomicInteger();
        var persistence=new EngineRegistryPersistence(()->{accesses.incrementAndGet();throw new SQLException("must not run");},"derby",()->null);
        assertThrows(java.io.IOException.class,()->persistence.write(null,"{}","invalid"));
        assertEquals(0,accesses.get());
    }

    @Test void legacyWholeRegistryReplacementGetsFreshEpochOnlyAfterCheckedCommit() throws Exception {
        try(Database db=new Database()) {
            RegistryStore[] holder=new RegistryStore[1];
            var persistence=new EngineRegistryPersistence(db::connection,"derby",()->holder[0]);
            var store=new RegistryStore(persistence);holder[0]=store;store.load(null);
            String before=store.epoch(), replacement=document();db.external(replacement);
            persistence.update(EngineRegistryPersistence.properties(replacement));
            assertNotEquals(before,store.epoch());
            assertNotEquals(PythonApi.mapper().readValue(replacement,PythonApi.Registry.class).registryEpoch,store.epoch());
            assertEquals(store.storedJson(),persistence.read());
            assertTrue(store.snapshot().recentMutationReceipts.isEmpty());
        }
    }

    @Test void invalidLegacyReplacementCannotPublishSpeculativeRegistry() throws Exception {
        try(Database db=new Database()) {
            RegistryStore[] holder=new RegistryStore[1];var persistence=new EngineRegistryPersistence(db::connection,"derby",()->holder[0]);
            var store=new RegistryStore(persistence);holder[0]=store;store.load(null);String before=store.storedJson();
            db.external("{}");assertThrows(com.mirth.connect.client.core.ControllerException.class,()->persistence.update(EngineRegistryPersistence.properties("{}")));
            assertEquals(before,store.storedJson());assertThrows(ApiFailure.class,store::snapshot);
        }
    }
}
