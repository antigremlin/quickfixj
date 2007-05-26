/*******************************************************************************
 * Copyright (c) quickfixengine.org  All rights reserved. 
 * 
 * This file is part of the QuickFIX FIX Engine 
 * 
 * This file may be distributed under the terms of the quickfixengine.org 
 * license as defined by quickfixengine.org and appearing in the file 
 * LICENSE included in the packaging of this file. 
 * 
 * This file is provided AS IS with NO WARRANTY OF ANY KIND, INCLUDING 
 * THE WARRANTY OF DESIGN, MERCHANTABILITY AND FITNESS FOR A 
 * PARTICULAR PURPOSE. 
 * 
 * See http://www.quickfixengine.org/LICENSE for licensing information. 
 * 
 * Contact ask@quickfixengine.org if any conditions of this licensing 
 * are not clear to you.
 ******************************************************************************/

package quickfix;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import junit.framework.TestCase;

import org.easymock.MockControl;

public class JdbcLogTest extends TestCase {
    private JdbcLog log;
    private JdbcLogFactory logFactory;
    private Connection connection;
    private SessionID sessionID;

    public void testLog() throws Exception {
        doLogTest(null);
    }

    public void testLogWithDataSource() throws Exception {
        // Set up mock data source
        MockControl mockDataSourceControl = MockControl.createControl(DataSource.class);
        DataSource mockDataSource = (DataSource) mockDataSourceControl.getMock();
        MockControl mockConnectionControl = MockControl.createControl(Connection.class);
        Connection mockConnection = (Connection) mockConnectionControl.getMock();
        MockControl mockPreparedStatementControl = MockControl.createNiceControl(PreparedStatement.class);
        PreparedStatement mockPreparedStatement = (PreparedStatement) mockPreparedStatementControl.getMock();

        mockDataSourceControl.expectAndReturn(mockDataSource.getConnection(), mockConnection);
        
        mockConnection.prepareStatement("");
        mockConnectionControl.setMatcher(MockControl.ALWAYS_MATCHER);
        mockConnectionControl.setReturnValue(mockPreparedStatement);

        mockConnection.close();
        
        mockDataSourceControl.replay();
        mockConnectionControl.replay();
        mockPreparedStatementControl.replay();
        
        // Invoke a log method to verify expected behavior
        setUpJdbcLog(false, mockDataSource);
        log.onIncoming("INCOMING");
        
        mockPreparedStatementControl.verify();
        mockConnectionControl.verify();
        mockDataSourceControl.verify();
    }

    private void doLogTest(DataSource dataSource) throws ClassNotFoundException, SQLException, ConfigError {
        setUpJdbcLog(false, dataSource);
        assertEquals(0, getRowCount(connection, "messages_log"));
        log.onIncoming("INCOMING");
        assertEquals(1, getRowCount(connection, "messages_log"));
        assertLogData(connection, 0, sessionID, "INCOMING", JdbcLog.MESSAGES_LOG_TABLE);

        log.onOutgoing("OUTGOING");
        assertEquals(2, getRowCount(connection, JdbcLog.MESSAGES_LOG_TABLE));
        assertLogData(connection, 0, sessionID, "INCOMING", JdbcLog.MESSAGES_LOG_TABLE);
        assertLogData(connection, 1, sessionID, "OUTGOING", JdbcLog.MESSAGES_LOG_TABLE);

        assertEquals(0, getRowCount(connection, "event_log"));
        log.onEvent("EVENT");
        assertEquals(1, getRowCount(connection, "event_log"));
        assertLogData(connection, 0, sessionID, "EVENT", "event_log");

        log.clear();
        assertEquals(0, getRowCount(connection, JdbcLog.MESSAGES_LOG_TABLE));
        assertEquals(0, getRowCount(connection, "event_log"));
    }

    public void testLogWithHeartbeatFiltering() throws Exception {
        setUpJdbcLog(false, null);
        
        assertEquals(0, getRowCount(connection, "messages_log"));
        log.onIncoming("INCOMING\00135=0\001");
        assertEquals(1, getRowCount(connection, "messages_log"));
        log.onOutgoing("OUTGOING\00135=0\001");
        assertEquals(2, getRowCount(connection, JdbcLog.MESSAGES_LOG_TABLE));

        setUpJdbcLog(true, null);
        
        assertEquals(0, getRowCount(connection, "messages_log"));
        log.onIncoming("INCOMING\00135=0\001");
        assertEquals(0, getRowCount(connection, "messages_log"));
        log.onOutgoing("OUTGOING\00135=0\001");
        assertEquals(0, getRowCount(connection, JdbcLog.MESSAGES_LOG_TABLE));
    }

    /** Make sure the logger handles the situation where the underlying JdbcLog is misconfigured
     * (such as we can't connect ot the DB, or the tables are missing) and doesn't try
     * to print failing exceptions recursively until the stack overflows
     */
    public void testHandlesRecursivelyFailingException() throws Exception {
        setUpJdbcLog(false, null);

        // need to register the session since we are going to log errors through LogUtil
        Session.registerSession(new Session(new UnitTestApplication(), new MemoryStoreFactory(),
                sessionID, new DataDictionary("FIX42.xml"), null, logFactory,
                new DefaultMessageFactory(), 0));

        // remove the messages and events tables
        connection.prepareStatement("DROP TABLE IF EXISTS " + JdbcLog.MESSAGES_LOG_TABLE + ";")
                .execute();
        connection.prepareStatement("DROP TABLE IF EXISTS " + JdbcLog.EVENT_LOG_TABLE + ";")
                .execute();

        // now try to log an error
        try {
            log.onIncoming("DB is messed up");
        } catch (OutOfMemoryError err) {
            fail("We seem to get an out of memory error b/c of stack overflow b/c we"
                    + "keep calling jdbc logger recursively in case of misconfiguration: "
                    + err.getMessage());
        } finally {
            // put the tables back so they can be cleaned up in tearDown()
            initializeTableDefinitions(connection);
        }

    }

    private void setUpJdbcLog(boolean filterHeartbeats, DataSource dataSource) throws ClassNotFoundException, SQLException, ConfigError {
        connection = JdbcTestSupport.getConnection();
        SessionSettings settings = new SessionSettings();
        if (filterHeartbeats) {
            settings.setBool(JdbcSetting.SETTING_JDBC_LOG_HEARTBEATS, false);
        }
        JdbcTestSupport.setHypersonicSettings(settings);
        initializeTableDefinitions(connection);
        logFactory = new JdbcLogFactory(settings);
        logFactory.setDataSource(dataSource);
        long now = System.currentTimeMillis();
        sessionID = new SessionID("FIX.4.2", "SENDER-" + now, "TARGET-" + now);
        settings.setString(sessionID, "ConnectionType", "acceptor");
        log = (JdbcLog) logFactory.create(sessionID);
        assertEquals(0, getRowCount(connection, JdbcLog.MESSAGES_LOG_TABLE));
    }

    private void assertLogData(Connection connection, int rowOffset, SessionID sessionID,
            String text, String tableName) throws SQLException {
        Statement s = connection.createStatement();
        ResultSet rs = s
                .executeQuery("select time,beginstring,sendercompid,targetcompid,session_qualifier,text from "
                        + tableName);
        int n = 0;
        while (rs.next() && n < rowOffset)
            n++;
        assertNotNull(sessionID.getBeginString(), rs.getDate("time"));
        assertEquals(sessionID.getBeginString(), rs.getString("beginstring"));
        assertEquals(sessionID.getSenderCompID(), rs.getString("sendercompid"));
        assertEquals(sessionID.getTargetCompID(), rs.getString("targetcompid"));
        assertEquals(sessionID.getSessionQualifier(), rs.getString("session_qualifier"));
        assertEquals(text, rs.getString("text"));
        rs.close();
        s.close();
    }

    private static int getRowCount(Connection connection, String tableName) throws SQLException {
        Statement s = connection.createStatement();
        ResultSet rs = s.executeQuery("select count(*) from " + tableName);
        if (rs.next()) {
            return rs.getInt(1);
        }
        rs.close();
        s.close();
        return 0;
    }

    private static void initializeTableDefinitions(Connection connection) throws ConfigError {
        try {
            JdbcTestSupport.loadSQL(connection,
                    "core/src/main/config/sql/mysql/messages_log_table.sql",
                    new JdbcTestSupport.HypersonicPreprocessor(null));
            JdbcTestSupport.loadSQL(connection,
                    "core/src/main/config/sql/mysql/event_log_table.sql",
                    new JdbcTestSupport.HypersonicPreprocessor(null));
        } catch (Exception e) {
            throw new ConfigError(e);
        }
    }

}
