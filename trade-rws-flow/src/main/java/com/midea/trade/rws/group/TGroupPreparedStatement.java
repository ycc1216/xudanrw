package com.midea.trade.rws.group;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.midea.trade.rws.dbselect.DBSelector.AbstractDataSourceTryer;
import com.midea.trade.rws.dbselect.DBSelector.DataSourceTryer;
import com.midea.trade.rws.parameter.ParameterContext;
import com.midea.trade.rws.parameter.ParameterMethod;
import com.midea.trade.rws.parameter.Parameters;
import com.midea.trade.rws.rule.SqlType;
import com.midea.trade.rws.util.GroupHintParser;
import com.midea.trade.rws.util.SQLParser;


public class TGroupPreparedStatement extends TGroupStatement implements PreparedStatement {	private static final Log log = LogFactory.getLog(TGroupPreparedStatement.class);
	private String sql;
	public TGroupPreparedStatement(TGroupDataSource tGroupDataSource, TGroupConnection tGroupConnection, String sql) {
		super(tGroupDataSource, tGroupConnection);
		this.sql = sql;
	}
	
	private int autoGeneratedKeys = -1;
	private int[] columnIndexes;
	private String[] columnNames;

	
	//参数列表到参数上下文的映射  如 1:name  2：'2011-11-11'
	protected Map<Integer, ParameterContext> parameterSettings = new HashMap<Integer, ParameterContext>();

	public void clearParameters() throws SQLException {
		parameterSettings.clear();
	}

	private PreparedStatement createPreparedStatementInternal(Connection conn, String sql) throws SQLException {
		PreparedStatement ps;
		if (autoGeneratedKeys != -1) {
			ps = conn.prepareStatement(sql, autoGeneratedKeys);
		} else if (columnIndexes != null) {
			ps = conn.prepareStatement(sql, columnIndexes);
		} else if (columnNames != null) {
			ps = conn.prepareStatement(sql, columnNames);
		} else {
			int resultSetHoldability = this.resultSetHoldability;
			if (resultSetHoldability == -1) //未调用过setResultSetHoldability
				resultSetHoldability = conn.getHoldability();

			ps = conn.prepareStatement(sql, this.resultSetType, this.resultSetConcurrency, resultSetHoldability);
		}
		setBaseStatement(ps);
		ps.setQueryTimeout(queryTimeout); //这句可能抛出异常，所以要放到setBaseStatement之后
	    ps.setFetchSize(fetchSize);
	    ps.setMaxRows(maxRows);

		return ps;
	}

	public boolean execute() throws SQLException {
		if (log.isDebugEnabled()) {
			log.debug("invoke execute, sql = " + sql);
		}

		SqlType sqlType = SQLParser.getSqlType(sql);
		if (sqlType == SqlType.SELECT || sqlType == SqlType.SELECT_FOR_UPDATE || sqlType == SqlType.SHOW) {
			executeQuery();
			return true;
		} else if (sqlType == SqlType.INSERT || sqlType == SqlType.UPDATE || sqlType == SqlType.DELETE||sqlType == SqlType.REPLACE
				||sqlType==SqlType.TRUNCATE|| sqlType == SqlType.CREATE|| sqlType== SqlType.DROP|| sqlType == SqlType.LOAD|| sqlType== SqlType.MERGE) {
            super.updateCount=executeUpdate();
			return false;
		} else {
			throw new SQLException("only select, insert, update, delete,truncate,create,drop,load,merge sql is supported");
		}
	}

	/* ========================================================================
	 * executeQuery逻辑
	 * ======================================================================*/
	public ResultSet executeQuery() throws SQLException {
		checkClosed();
		ensureResultSetIsEmpty();
		recordReadTimes();
		increaseConcurrentRead();
		
		boolean gotoRead = SqlType.SELECT.equals(SQLParser.getSqlType(sql)) && tGroupConnection.getAutoCommit();
		Connection conn = tGroupConnection.getBaseConnection(sql,gotoRead);

		try {
			if (conn != null){
				sql=GroupHintParser.removeTddlGroupHint(sql);
				return executeQueryOnConnection(conn, sql);
			}else{
				// hint优先
				Integer dataSourceIndex = GroupHintParser.convertHint2Index(sql);
				sql=GroupHintParser.removeTddlGroupHint(sql);
				if (dataSourceIndex < 0) {
					dataSourceIndex = ThreadLocalDataSourceIndex.getIndex();
				}
				return tGroupDataSource.getDBSelector(gotoRead).tryExecute(executeQueryTryer, retryingTimes, sql,dataSourceIndex);
			}
		} catch (SQLException e) {
			throw e;
		}finally {
			decreaseConcurrentRead();
		}
	}

	@Override
	protected ResultSet executeQueryOnConnection(Connection conn, String sql) throws SQLException {
		PreparedStatement ps = createPreparedStatementInternal(conn, sql);
		Parameters.setParameters(ps, parameterSettings);
		this.currentResultSet = ps.executeQuery();
		return this.currentResultSet;
	}

	/* ========================================================================
	 * executeUpdate逻辑
	 * ======================================================================*/
	public int executeUpdate() throws SQLException {
		checkClosed();
		ensureResultSetIsEmpty();
		recordWriteTimes();
		increaseConcurrentWrite();
		Connection conn = tGroupConnection.getBaseConnection(sql,false);
		try {
			if (conn != null){
				sql=GroupHintParser.removeTddlGroupHint(sql);
				//#bug 2011-10-28,modify by junyu,updateCount not set,fixed
				int updateCount=executeUpdateOnConnection(conn);
				super.updateCount=updateCount;
				return updateCount;
			}else{
				// hint优先
				Integer dataSourceIndex = GroupHintParser
							.convertHint2Index(sql);
				sql=GroupHintParser.removeTddlGroupHint(sql);
				if (dataSourceIndex < 0) {
					dataSourceIndex = ThreadLocalDataSourceIndex.getIndex();
				}
				//#bug 2011-10-28,modify by junyu,updateCount not set,fixed
				int updateCount=tGroupDataSource.getDBSelector(false).tryExecute(null, executeUpdateTryer, retryingTimes,sql,dataSourceIndex);
				super.updateCount=updateCount;
				return updateCount;
			}
		} catch (SQLException e) {
			throw e;
		}finally {
			decreaseConcurrentWrite();
		}
	}

	private int executeUpdateOnConnection(Connection conn) throws SQLException {
		PreparedStatement ps = createPreparedStatementInternal(conn, sql);
		Parameters.setParameters(ps, parameterSettings);
		return ps.executeUpdate();
	}

	private DataSourceTryer<Integer> executeUpdateTryer = new AbstractDataSourceTryer<Integer>() {
		public Integer tryOnDataSource(DataSourceWrapper dsw, Object... args) throws SQLException {
			Connection conn = TGroupPreparedStatement.this.tGroupConnection.createNewConnection(dsw, false);
			return executeUpdateOnConnection(conn);
		}
	};

	public ResultSetMetaData getMetaData() throws SQLException {
		throw new UnsupportedOperationException("getMetaData");
	}

	public ParameterMetaData getParameterMetaData() throws SQLException {
		throw new UnsupportedOperationException("getParameterMetaData");
	}

	/* ========================================================================
	 * setxxx SQL参数设置
	 * ======================================================================*/
	public void setArray(int i, Array x) throws SQLException {
		parameterSettings.put(i, new ParameterContext(ParameterMethod.setArray, new Object[] { i, x }));
	}

	public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setAsciiStream, new Object[] {
				parameterIndex, x, length }));
	}

	public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setBigDecimal, new Object[] {
				parameterIndex, x }));
	}

	public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setBinaryStream, new Object[] {
				parameterIndex, x, length }));
	}

	public void setBlob(int i, Blob x) throws SQLException {
		parameterSettings.put(i, new ParameterContext(ParameterMethod.setBlob, new Object[] { i, x }));
	}

	public void setBoolean(int parameterIndex, boolean x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setBoolean, new Object[] {
				parameterIndex, x }));
	}

	public void setByte(int parameterIndex, byte x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setByte, new Object[] {
				parameterIndex, x }));
	}

	public void setBytes(int parameterIndex, byte[] x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setBytes, new Object[] {
				parameterIndex, x }));
	}

	public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setCharacterStream, new Object[] {
				parameterIndex, reader, length }));
	}

	public void setClob(int i, Clob x) throws SQLException {
		parameterSettings.put(i, new ParameterContext(ParameterMethod.setClob, new Object[] { i, x }));
	}

	public void setDate(int parameterIndex, Date x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setDate1, new Object[] {
				parameterIndex, x }));
	}

	public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setDate2, new Object[] {
				parameterIndex, x, cal }));
	}

	public void setDouble(int parameterIndex, double x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setDouble, new Object[] {
				parameterIndex, x }));
	}

	public void setFloat(int parameterIndex, float x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setFloat, new Object[] {
				parameterIndex, x }));
	}

	public void setInt(int parameterIndex, int x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setInt, new Object[] {
				parameterIndex, x }));
	}

	public void setLong(int parameterIndex, long x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setLong, new Object[] {
				parameterIndex, x }));
	}

	public void setNull(int parameterIndex, int sqlType) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setNull1, new Object[] {
				parameterIndex, sqlType }));
	}

	public void setNull(int paramIndex, int sqlType, String typeName) throws SQLException {
		parameterSettings.put(paramIndex, new ParameterContext(ParameterMethod.setNull2, new Object[] { paramIndex,
				sqlType, typeName }));
	}

	public void setObject(int parameterIndex, Object x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setObject1, new Object[] {
				parameterIndex, x }));
	}

	public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setObject2, new Object[] {
				parameterIndex, x, targetSqlType }));
	}

	public void setObject(int parameterIndex, Object x, int targetSqlType, int scale) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setObject3, new Object[] {
				parameterIndex, x, targetSqlType, scale }));
	}

	public void setRef(int i, Ref x) throws SQLException {
		parameterSettings.put(i, new ParameterContext(ParameterMethod.setRef, new Object[] { i, x }));
	}

	public void setShort(int parameterIndex, short x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setShort, new Object[] {
				parameterIndex, x }));
	}

	public void setString(int parameterIndex, String x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setString, new Object[] {
				parameterIndex, x }));
	}

	public void setTime(int parameterIndex, Time x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setTime1, new Object[] {
				parameterIndex, x }));
	}

	public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setTime2, new Object[] {
				parameterIndex, x, cal }));
	}

	public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setTimestamp1, new Object[] {
				parameterIndex, x }));
	}

	public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setTimestamp2, new Object[] {
				parameterIndex, x, cal }));
	}

	public void setURL(int parameterIndex, URL x) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setURL, new Object[] {
				parameterIndex, x }));
	}

	@Deprecated
	public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
		parameterSettings.put(parameterIndex, new ParameterContext(ParameterMethod.setUnicodeStream, new Object[] {
				parameterIndex, x, length }));
	}

	/* ========================================================================
	 * executeBatch
	 * ======================================================================*/
	private List<Map<Integer, ParameterContext>> pstArgs;

	public void addBatch() throws SQLException {
		if (pstArgs == null) {
			pstArgs = new LinkedList<Map<Integer, ParameterContext>>();
		}
		Map<Integer, ParameterContext> newArg = new HashMap<Integer, ParameterContext>(parameterSettings.size());
		newArg.putAll(parameterSettings);

		parameterSettings.clear();

		pstArgs.add(newArg);
	}

	public int[] executeBatch() throws SQLException {
		try {
			checkClosed();
			ensureResultSetIsEmpty();

			if (pstArgs == null || pstArgs.isEmpty()) {
				return new int[0];
			}

			Connection conn = tGroupConnection.getBaseConnection(sql,false);

			if (conn != null) {
				sql=GroupHintParser.removeTddlGroupHint(sql);
				// 如果当前已经有连接,则不做任何重试。对于更新来说，不管有没有事务，
				// 用户总期望getConnection获得连接之后，后续的一系列操作都在这同一个库，同一个连接上执行
				return executeBatchOnConnection(conn);
			} else {
				Integer dataSourceIndex = GroupHintParser
				    .convertHint2Index(sql);
				sql=GroupHintParser.removeTddlGroupHint(sql);
				if (dataSourceIndex < 0) {
					dataSourceIndex = ThreadLocalDataSourceIndex.getIndex();
				}
				return tGroupDataSource.getDBSelector(false).tryExecute(null, executeBatchTryer, retryingTimes,dataSourceIndex);
			}
		} finally {
			if (pstArgs != null)
				pstArgs.clear();
		}
	}

	private DataSourceTryer<int[]> executeBatchTryer = new AbstractDataSourceTryer<int[]>() {
		public int[] tryOnDataSource(DataSourceWrapper dsw, Object... args) throws SQLException {
			Connection conn = tGroupConnection.createNewConnection(dsw, false);
			return executeBatchOnConnection(conn);
		}
	};

	//TODO 重试中Statement的关闭
	private int[] executeBatchOnConnection(Connection conn) throws SQLException {
		PreparedStatement ps = createPreparedStatementInternal(conn, sql);

		for (Map<Integer, ParameterContext> parameterSettings : pstArgs) {
			setBatchParameters(ps, parameterSettings.values());
			ps.addBatch();
		}

		return ps.executeBatch();
	}

	private static void setBatchParameters(PreparedStatement ps, Collection<ParameterContext> batchedParameters)
			throws SQLException {
		for (ParameterContext context : batchedParameters) {
			Parameters.parameterHandlers.get(context.getParameterMethod()).setParameter(ps, context.getArgs());
		}
	}

	/* ========================================================================
	 * 无逻辑的getter/setter
	 * ======================================================================*/
	public int getAutoGeneratedKeys() {
		return autoGeneratedKeys;
	}

	public void setAutoGeneratedKeys(int autoGeneratedKeys) {
		this.autoGeneratedKeys = autoGeneratedKeys;
	}

	public int[] getColumnIndexes() {
		return columnIndexes;
	}

	public void setColumnIndexes(int[] columnIndexes) {
		this.columnIndexes = columnIndexes;
	}

	public String[] getColumnNames() {
		return columnNames;
	}

	public void setColumnNames(String[] columnNames) {
		this.columnNames = columnNames;
	}

	public boolean isClosed() throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setPoolable(boolean poolable) throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public boolean isPoolable() throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public <T> T unwrap(Class<T> iface) throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public boolean isWrapperFor(Class<?> iface) throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setRowId(int parameterIndex, RowId x) throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setNString(int parameterIndex, String value)
			throws SQLException
	{
		throw new SQLException("not support exception");

	}

	public void setNCharacterStream(int parameterIndex, Reader value,
			long length) throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setNClob(int parameterIndex, NClob value) throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setClob(int parameterIndex, Reader reader, long length)
			throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setBlob(int parameterIndex, InputStream inputStream, long length)
			throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setNClob(int parameterIndex, Reader reader, long length)
			throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setSQLXML(int parameterIndex, SQLXML xmlObject)
			throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setAsciiStream(int parameterIndex, InputStream x, long length)
			throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setBinaryStream(int parameterIndex, InputStream x, long length)
			throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setCharacterStream(int parameterIndex, Reader reader,
			long length) throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setAsciiStream(int parameterIndex, InputStream x)
			throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setBinaryStream(int parameterIndex, InputStream x)
			throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setCharacterStream(int parameterIndex, Reader reader)
			throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setNCharacterStream(int parameterIndex, Reader value)
			throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setClob(int parameterIndex, Reader reader) throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setBlob(int parameterIndex, InputStream inputStream)
			throws SQLException
	{
		throw new SQLException("not support exception");
	}

	public void setNClob(int parameterIndex, Reader reader) throws SQLException
	{
		throw new SQLException("not support exception");
	}
}
