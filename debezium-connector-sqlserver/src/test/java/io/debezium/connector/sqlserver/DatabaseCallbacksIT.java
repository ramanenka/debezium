/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.sqlserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.debezium.config.Configuration;
import io.debezium.connector.sqlserver.util.TestHelper;
import io.debezium.embedded.AbstractConnectorTest;
import io.debezium.relational.ChangeTable;
import io.debezium.util.Testing;

public class DatabaseCallbacksIT extends AbstractConnectorTest {
    private SqlServerConnection connection;

    @Before
    public void before() throws SQLException {
        TestHelper.createTestDatabase();
        connection = TestHelper.testConnection();
        connection.execute("CREATE TABLE tablea (id int primary key, cola varchar(30))");
        TestHelper.enableTableCdc(connection, "tablea", "tablea_c1");
        createDeleteCaptureInstanceProcedure();
        initializeConnectorTestFramework();
        Testing.Files.delete(TestHelper.SCHEMA_HISTORY_PATH);
    }

    @After
    public void after() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void testDeleteCaptureInstanceCalledOnCommit() throws InterruptedException, SQLException {
        final Configuration config = TestHelper.defaultConfig()
                .with(SqlServerConnectorConfig.DATABASE_CALLBACKS, true)
                .build();

        start(SqlServerConnector.class, config);
        assertConnectorIsRunning();
        TestHelper.waitForStreamingStarted();

        connection.execute("INSERT INTO tablea VALUES(1, 'a')");
        assertEquals(1, consumeRecords(1));

        connection.execute("ALTER TABLE tablea ADD colb int NULL");
        TestHelper.enableTableCdc(connection, "tablea", "tablea_c2");
        connection.execute("INSERT INTO tablea VALUES(2, 'b', 2)");
        assertEquals(1, consumeRecords(1));
        waitForChangeTableToBeGone("tablea_c1");

        connection.execute("ALTER TABLE tablea ADD colc int NULL");
        TestHelper.enableTableCdc(connection, "tablea", "tablea_c3");
        connection.execute("INSERT INTO tablea VALUES(3, 'b', 3, 4)");
        assertEquals(1, consumeRecords(1));
        waitForChangeTableToBeGone("tablea_c2");

        stopConnector();
    }

    @Test
    public void testDeleteCaptureInstanceFailsOnCommit() throws InterruptedException, SQLException, TimeoutException {
        final Configuration config = TestHelper.defaultConfig()
                .with(SqlServerConnectorConfig.DATABASE_CALLBACKS, true)
                .build();

        start(SqlServerConnector.class, config, (boolean success, String message, Throwable error) -> {
            assertFalse(success);
            assertTrue(message.contains("Could not find stored procedure"));
            assertNotNull(error);
        });

        assertConnectorIsRunning();
        TestHelper.waitForStreamingStarted();

        dropCompletedReadingFromCaptureInstanceProcedure();

        connection.execute("INSERT INTO tablea VALUES(1, 'a')");
        assertEquals(1, consumeRecords(1));

        connection.execute("ALTER TABLE tablea ADD colb int NULL");
        TestHelper.enableTableCdc(connection, "tablea", "tablea_c2");
        connection.execute("INSERT INTO tablea VALUES(2, 'b', 2)");
        assertEquals(1, consumeRecords(1));

        waitForEngineToStop();
        stopConnector();

        assertTrue(existingChangeTableNames().contains("tablea_c1"));

        createDeleteCaptureInstanceProcedure();

        start(SqlServerConnector.class, config, (boolean success, String message, Throwable error) -> {
            assertTrue(success);
            assertTrue(message.isEmpty());
            assertNull(error);
        });
        assertConnectorIsRunning();
        TestHelper.waitForStreamingStarted();

        connection.execute("INSERT INTO tablea VALUES(3, 'b', 3)");
        assertEquals(1, consumeRecords(1));
        waitForChangeTableToBeGone("tablea_c1");

        connection.execute("INSERT INTO tablea VALUES(4, 'b', 4)");
        assertEquals(1, consumeRecords(1));

        stopConnector();
    }

    private void waitForChangeTableToBeGone(String name) {
        Awaitility.await("Awaiting " + name + " change table to be gone")
                .atMost(Duration.ofMinutes(5))
                .until(() -> !existingChangeTableNames().contains(name));
    }

    private void waitForEngineToStop() {
        Awaitility.await("Awaiting for the engine to stop")
                .atMost(Duration.ofMinutes(5))
                .until(() -> !engine.isRunning());
    }

    private void createDeleteCaptureInstanceProcedure() throws SQLException {
        connection.execute("CREATE OR ALTER PROCEDURE dbo.DebeziumSQLConnector_CompletedReadingFromCaptureInstance\n" +
                "(@CaptureInstanceName sysname,\n" +
                "@StartLSN varchar(100),\n" +
                "@StopLSN varchar(100),\n" +
                "@Debug bit = NULL\n" +
                ") AS BEGIN\n" +
                "    DECLARE @source_schema sysname;\n" +
                "    DECLARE @source_name sysname;\n" +
                "\n" +
                "    SELECT \n" +
                "        @source_schema = OBJECT_SCHEMA_NAME(ct.source_object_id),\n" +
                "        @source_name = OBJECT_NAME(ct.source_object_id)\n" +
                "    FROM cdc.change_tables ct \n" +
                "    WHERE ct.capture_instance = @CaptureInstanceName;\n" +
                "\n" +
                "    EXEC sys.sp_cdc_disable_table \n" +
                "        @source_schema = @source_schema, \n" +
                "        @source_name = @source_name, \n" +
                "        @capture_instance = @CaptureInstanceName\n" +
                "END\n");
    }

    private void dropCompletedReadingFromCaptureInstanceProcedure() throws SQLException {
        connection.execute("DROP PROCEDURE dbo.DebeziumSQLConnector_CompletedReadingFromCaptureInstance");
    }

    private List<String> existingChangeTableNames() throws SQLException {
        return connection
                .getChangeTables(TestHelper.TEST_DATABASE_1)
                .stream()
                .map(ChangeTable::getCaptureInstance)
                .collect(Collectors.toList());
    }
}
