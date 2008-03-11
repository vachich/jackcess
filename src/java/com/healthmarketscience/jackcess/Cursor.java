/*
Copyright (c) 2007 Health Market Science, Inc.

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 2.1 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307
USA

You can contact Health Market Science at info@healthmarketscience.com
or at the following address:

Health Market Science
2700 Horizon Drive
Suite 200
King of Prussia, PA 19406
*/

package com.healthmarketscience.jackcess;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import com.healthmarketscience.jackcess.Table.RowState;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
 * Manages iteration for a Table.  Different cursors provide different methods
 * of traversing a table.  Cursors should be fairly robust in the face of
 * table modification during traversal (although depending on how the table is
 * traversed, row updates may or may not be seen).  Multiple cursors may
 * traverse the same table simultaneously.
 * <p>
 * The Cursor provides a variety of static utility methods to construct
 * cursors with given characteristics or easily search for specific values.
 * For even friendlier and more flexible construction, see
 * {@link CursorBuilder}.
 * <p>
 * Is not thread-safe.
 *
 * @author james
 */
public abstract class Cursor implements Iterable<Map<String, Object>>
{  
  private static final Log LOG = LogFactory.getLog(Cursor.class);  

  /** first position for the TableScanCursor */
  private static final ScanPosition FIRST_SCAN_POSITION =
    new ScanPosition(RowId.FIRST_ROW_ID);
  /** last position for the TableScanCursor */
  private static final ScanPosition LAST_SCAN_POSITION =
    new ScanPosition(RowId.LAST_ROW_ID);

  /** identifier for this cursor */
  private final Id _id;
  /** owning table */
  private final Table _table;
  /** State used for reading the table rows */
  private final RowState _rowState;
  /** the first (exclusive) row id for this cursor */
  private final Position _firstPos;
  /** the last (exclusive) row id for this cursor */
  private final Position _lastPos;
  /** the previous row */
  private Position _prevPos;
  /** the current row */
  private Position _curPos;
  

  protected Cursor(Id id, Table table, Position firstPos, Position lastPos) {
    _id = id;
    _table = table;
    _rowState = _table.createRowState();
    _firstPos = firstPos;
    _lastPos = lastPos;
    _curPos = firstPos;
    _prevPos = firstPos;
  }

  /**
   * Creates a normal, un-indexed cursor for the given table.
   * @param table the table over which this cursor will traverse
   */
  public static Cursor createCursor(Table table) {
    return new TableScanCursor(table);
  }

  /**
   * Creates an indexed cursor for the given table.
   * @param table the table over which this cursor will traverse
   * @param index index for the table which will define traversal order as
   *              well as enhance certain lookups
   */
  public static Cursor createIndexCursor(Table table, Index index)
    throws IOException
  {
    return createIndexCursor(table, index, null, null);
  }

  /**
   * Creates an indexed cursor for the given table, narrowed to the given
   * range.
   * @param table the table over which this cursor will traverse
   * @param index index for the table which will define traversal order as
   *              well as enhance certain lookups
   * @param startRow the first row of data for the cursor (inclusive), or
   *                 {@code null} for the first entry
   * @param endRow the last row of data for the cursor (inclusive), or
   *               {@code null} for the last entry
   */
  public static Cursor createIndexCursor(Table table, Index index,
                                         Object[] startRow, Object[] endRow)
    throws IOException
  {
    return createIndexCursor(table, index, startRow, true, endRow, true);
  }
  
  /**
   * Creates an indexed cursor for the given table, narrowed to the given
   * range.
   * @param table the table over which this cursor will traverse
   * @param index index for the table which will define traversal order as
   *              well as enhance certain lookups
   * @param startRow the first row of data for the cursor, or {@code null} for
   *                 the first entry
   * @param startInclusive whether or not startRow is inclusive or exclusive
   * @param endRow the last row of data for the cursor, or {@code null} for
   *               the last entry
   * @param endInclusive whether or not endRow is inclusive or exclusive
   */
  public static Cursor createIndexCursor(Table table, Index index,
                                         Object[] startRow,
                                         boolean startInclusive,
                                         Object[] endRow,
                                         boolean endInclusive)
    throws IOException
  {
    if(table != index.getTable()) {
      throw new IllegalArgumentException(
          "Given index is not for given table: " + index + ", " + table);
    }
    return new IndexCursor(table, index,
                           index.cursor(startRow, startInclusive,
                                        endRow, endInclusive));
  }

  /**
   * Convenience method for finding a specific row in a table which matches a
   * given row "pattern.  See {@link #findRow(Map)} for details on the
   * rowPattern.
   * 
   * @param table the table to search
   * @param rowPattern pattern to be used to find the row
   * @return the matching row or {@code null} if a match could not be found.
   */
  public static Map<String,Object> findRow(Table table,
                                           Map<String,Object> rowPattern)
    throws IOException
  {
    Cursor cursor = createCursor(table);
    if(cursor.findRow(rowPattern)) {
      return cursor.getCurrentRow();
    }
    return null;
  }
  
  /**
   * Convenience method for finding a specific row in a table which matches a
   * given row "pattern.  See {@link #findRow(Column,Object)} for details on
   * the pattern.
   * <p>
   * Note, a {@code null} result value is ambiguous in that it could imply no
   * match or a matching row with {@code null} for the desired value.  If
   * distinguishing this situation is important, you will need to use a Cursor
   * directly instead of this convenience method.
   * 
   * @param table the table to search
   * @param column column whose value should be returned
   * @param columnPattern column being matched by the valuePattern
   * @param valuePattern value from the columnPattern which will match the
   *                     desired row
   * @return the matching row or {@code null} if a match could not be found.
   */
  public static Object findValue(Table table, Column column,
                                 Column columnPattern, Object valuePattern)
    throws IOException
  {
    Cursor cursor = createCursor(table);
    if(cursor.findRow(columnPattern, valuePattern)) {
      return cursor.getCurrentRowValue(column);
    }
    return null;
  }
  
  /**
   * Convenience method for finding a specific row in an indexed table which
   * matches a given row "pattern.  See {@link #findRow(Map)} for details on
   * the rowPattern.
   * 
   * @param table the table to search
   * @param index index to assist the search
   * @param rowPattern pattern to be used to find the row
   * @return the matching row or {@code null} if a match could not be found.
   */
  public static Map<String,Object> findRow(Table table, Index index,
                                           Map<String,Object> rowPattern)
    throws IOException
  {
    Cursor cursor = createIndexCursor(table, index);
    if(cursor.findRow(rowPattern)) {
      return cursor.getCurrentRow();
    }
    return null;
  }
  
  /**
   * Convenience method for finding a specific row in a table which matches a
   * given row "pattern.  See {@link #findRow(Column,Object)} for details on
   * the pattern.
   * <p>
   * Note, a {@code null} result value is ambiguous in that it could imply no
   * match or a matching row with {@code null} for the desired value.  If
   * distinguishing this situation is important, you will need to use a Cursor
   * directly instead of this convenience method.
   * 
   * @param table the table to search
   * @param index index to assist the search
   * @param column column whose value should be returned
   * @param columnPattern column being matched by the valuePattern
   * @param valuePattern value from the columnPattern which will match the
   *                     desired row
   * @return the matching row or {@code null} if a match could not be found.
   */
  public static Object findValue(Table table, Index index, Column column,
                                 Column columnPattern, Object valuePattern)
    throws IOException
  {
    Cursor cursor = createIndexCursor(table, index);
    if(cursor.findRow(columnPattern, valuePattern)) {
      return cursor.getCurrentRowValue(column);
    }
    return null;
  }
  
  public Id getId() {
    return _id;
  }

  public Table getTable() {
    return _table;
  }

  public Index getIndex() {
    return null;
  }
  
  public JetFormat getFormat() {
    return getTable().getFormat();
  }

  public PageChannel getPageChannel() {
    return getTable().getPageChannel();
  }

  /**
   * Returns the current state of the cursor which can be restored at a future
   * point in time by a call to {@link #restoreSavepoint}.
   * <p>
   * Savepoints may be used across different cursor instances for the same
   * table, but they must have the same {@link Id}.
   */
  public Savepoint getSavepoint() {
    return new Savepoint(_id, _curPos, _prevPos);
  }

  /**
   * Moves the cursor to a savepoint previously returned from
   * {@link #getSavepoint}.
   * @throws IllegalArgumentException if the given savepoint does not have a
   *         cursorId equal to this cursor's id
   */
  public void restoreSavepoint(Savepoint savepoint)
    throws IOException
  {
    if(!_id.equals(savepoint.getCursorId())) {
      throw new IllegalArgumentException(
          "Savepoint " + savepoint + " is not valid for this cursor with id "
          + _id);
    }
    restorePosition(savepoint.getCurrentPosition(),
                    savepoint.getPreviousPosition());
  }
  
  /**
   * Returns the first row id (exclusive) as defined by this cursor.
   */
  protected Position getFirstPosition() {
    return _firstPos;
  }
  
  /**
   * Returns the last row id (exclusive) as defined by this cursor.
   */
  protected Position getLastPosition() {
    return _lastPos;
  }

  /**
   * Resets this cursor for forward traversal.  Calls {@link #beforeFirst}.
   */
  public void reset() {
    beforeFirst();
  }  

  /**
   * Resets this cursor for forward traversal (sets cursor to before the first
   * row).
   */
  public void beforeFirst() {
    reset(true);
  }
  
  /**
   * Resets this cursor for reverse traversal (sets cursor to after the last
   * row).
   */
  public void afterLast() {
    reset(false);
  }

  /**
   * Returns {@code true} if the cursor is currently positioned before the
   * first row, {@code false} otherwise.
   */
  public boolean isBeforeFirst()
    throws IOException
  {
    if(getFirstPosition().equals(_curPos)) {
      return !recheckPosition(false);
    }
    return false;
  }
  
  /**
   * Returns {@code true} if the cursor is currently positioned after the
   * last row, {@code false} otherwise.
   */
  public boolean isAfterLast()
    throws IOException
  {
    if(getLastPosition().equals(_curPos)) {
      return !recheckPosition(true);
    }
    return false;
  }

  /**
   * Returns {@code true} if the row at which the cursor is currently
   * positioned is deleted, {@code false} otherwise (including invalid rows).
   */
  public boolean isCurrentRowDeleted()
    throws IOException
  {
    // we need to ensure that the "deleted" flag has been read for this row
    // (or re-read if the table has been recently modified)
    Table.positionAtRowData(_rowState, _curPos.getRowId());
    return _rowState.isDeleted();
  }
  
  /**
   * Resets this cursor for traversing the given direction.
   */
  protected void reset(boolean moveForward) {
    _curPos = getDirHandler(moveForward).getBeginningPosition();
    _prevPos = _curPos;
    _rowState.reset();
  }  

  /**
   * Returns an Iterable whose iterator() method calls <code>afterLast</code>
   * on this cursor and returns an unmodifiable Iterator which will iterate
   * through all the rows of this table in reverse order.  Use of the Iterator
   * follows the same restrictions as a call to <code>getPreviousRow</code>.
   * @throws IllegalStateException if an IOException is thrown by one of the
   *         operations, the actual exception will be contained within
   */
  public Iterable<Map<String, Object>> reverseIterable() {
    return reverseIterable(null);
  }
  
  /**
   * Returns an Iterable whose iterator() method calls <code>afterLast</code>
   * on this table and returns an unmodifiable Iterator which will iterate
   * through all the rows of this table in reverse order, returning only the
   * given columns.  Use of the Iterator follows the same restrictions as a
   * call to <code>getPreviousRow</code>.
   * @throws IllegalStateException if an IOException is thrown by one of the
   *         operations, the actual exception will be contained within
   */
  public Iterable<Map<String, Object>> reverseIterable(
      final Collection<String> columnNames)
  {
    return new Iterable<Map<String, Object>>() {
      public Iterator<Map<String, Object>> iterator() {
        return new RowIterator(columnNames, false);
      }
    };
  }
  
  /**
   * Calls <code>beforeFirst</code> on this cursor and returns an unmodifiable
   * Iterator which will iterate through all the rows of this table.  Use of
   * the Iterator follows the same restrictions as a call to
   * <code>getNextRow</code>.
   * @throws IllegalStateException if an IOException is thrown by one of the
   *         operations, the actual exception will be contained within
   */
  public Iterator<Map<String, Object>> iterator()
  {
    return iterator(null);
  }
  
  /**
   * Returns an Iterable whose iterator() method returns the result of a call
   * to {@link #iterator(Collection<String>)}
   * @throws IllegalStateException if an IOException is thrown by one of the
   *         operations, the actual exception will be contained within
   */
  public Iterable<Map<String, Object>> iterable(
      final Collection<String> columnNames)
  {
    return new Iterable<Map<String, Object>>() {
      public Iterator<Map<String, Object>> iterator() {
        return Cursor.this.iterator(columnNames);
      }
    };
  }
  
  /**
   * Calls <code>beforeFirst</code> on this table and returns an unmodifiable
   * Iterator which will iterate through all the rows of this table, returning
   * only the given columns.  Use of the Iterator follows the same
   * restrictions as a call to <code>getNextRow</code>.
   * @throws IllegalStateException if an IOException is thrown by one of the
   *         operations, the actual exception will be contained within
   */
  public Iterator<Map<String, Object>> iterator(Collection<String> columnNames)
  {
    return new RowIterator(columnNames, true);
  }

  /**
   * Delete the current row.
   * @throws IllegalStateException if the current row is not valid (at
   *         beginning or end of table), or already deleted.
   */
  public void deleteCurrentRow() throws IOException {
    _table.deleteRow(_rowState, _curPos.getRowId());
  }

  /**
   * Moves to the next row in the table and returns it.
   * @return The next row in this table (Column name -> Column value), or
   *         {@code null} if no next row is found
   */
  public Map<String, Object> getNextRow() throws IOException {
    return getNextRow(null);
  }

  /**
   * Moves to the next row in the table and returns it.
   * @param columnNames Only column names in this collection will be returned
   * @return The next row in this table (Column name -> Column value), or
   *         {@code null} if no next row is found
   */
  public Map<String, Object> getNextRow(Collection<String> columnNames) 
    throws IOException
  {
    return getAnotherRow(columnNames, true);
  }

  /**
   * Moves to the previous row in the table and returns it.
   * @return The previous row in this table (Column name -> Column value), or
   *         {@code null} if no previous row is found
   */
  public Map<String, Object> getPreviousRow() throws IOException {
    return getPreviousRow(null);
  }

  /**
   * Moves to the previous row in the table and returns it.
   * @param columnNames Only column names in this collection will be returned
   * @return The previous row in this table (Column name -> Column value), or
   *         {@code null} if no previous row is found
   */
  public Map<String, Object> getPreviousRow(Collection<String> columnNames) 
    throws IOException
  {
    return getAnotherRow(columnNames, false);
  }


  /**
   * Moves to another row in the table based on the given direction and
   * returns it.
   * @param columnNames Only column names in this collection will be returned
   * @return another row in this table (Column name -> Column value), where
   *         "next" may be backwards if moveForward is {@code false}, or
   *         {@code null} if there is not another row in the given direction.
   */
  private Map<String, Object> getAnotherRow(Collection<String> columnNames,
                                            boolean moveForward) 
    throws IOException
  {
    if(moveToAnotherRow(moveForward)) {
      return getCurrentRow(columnNames);
    }
    return null;
  }  

  /**
   * Moves to the next row as defined by this cursor.
   * @return {@code true} if a valid next row was found, {@code false}
   *         otherwise
   */
  public boolean moveToNextRow()
    throws IOException
  {
    return moveToAnotherRow(true);
  }

  /**
   * Moves to the previous row as defined by this cursor.
   * @return {@code true} if a valid previous row was found, {@code false}
   *         otherwise
   */
  public boolean moveToPreviousRow()
    throws IOException
  {
    return moveToAnotherRow(false);
  }

  /**
   * Moves to another row in the given direction as defined by this cursor.
   * @return {@code true} if another valid row was found in the given
   *         direction, {@code false} otherwise
   */
  private boolean moveToAnotherRow(boolean moveForward)
    throws IOException
  {
    if(_curPos.equals(getDirHandler(moveForward).getEndPosition())) {
      // already at end, make sure nothing has changed
      return recheckPosition(moveForward);
    }

    return moveToAnotherRowImpl(moveForward);
  }

  /**
   * Restores a current position for the cursor (current position becomes
   * previous position).
   */
  protected void restorePosition(Position curPos)
    throws IOException
  {
    restorePosition(curPos, _curPos);
  }
    
  /**
   * Restores a current and previous position for the cursor if the given
   * positions are different from the current positions.
   */
  protected final void restorePosition(Position curPos, Position prevPos)
    throws IOException
  {
    if(!curPos.equals(_curPos) || !prevPos.equals(_prevPos)) {
      restorePositionImpl(curPos, prevPos);
    }
  }

  /**
   * Restores a current and previous position for the cursor.
   */
  protected void restorePositionImpl(Position curPos, Position prevPos)
    throws IOException
  {
    // make the current position previous, and the new position current
    _prevPos = _curPos;
    _curPos = curPos;
    _rowState.reset();
  }
  
  /**
   * Rechecks the current position if the underlying data structures have been
   * modified.
   * @return {@code true} if the cursor ended up in a new position,
   *         {@code false} otherwise.
   */
  private boolean recheckPosition(boolean moveForward)
    throws IOException
  {
    if(isUpToDate()) {
      // nothing has changed
      return false;
    }

    // move the cursor back to the previous position
    restorePosition(_prevPos);
    return moveToAnotherRowImpl(moveForward);
  }

  /**
   * Does the grunt work of moving the cursor to another position in the given
   * direction.
   */
  private boolean moveToAnotherRowImpl(boolean moveForward)
    throws IOException
  {
    _rowState.reset();
    _prevPos = _curPos;
    _curPos = findAnotherPosition(_rowState, _curPos, moveForward);
    Table.positionAtRowHeader(_rowState, _curPos.getRowId());
    return(!_curPos.equals(getDirHandler(moveForward).getEndPosition()));
  }
  
  /**
   * Moves to the first row (as defined by the cursor) where the given column
   * has the given value.  This may be more efficient on some cursors than
   * others.  If a match is not found (or an exception is thrown), the cursor
   * is restored to its previous state.
   *
   * @param columnPattern column from the table for this cursor which is being
   *                      matched by the valuePattern
   * @param valuePattern value which is equal to the corresponding value in
   *                     the matched row
   * @return {@code true} if a valid row was found with the given value,
   *         {@code false} if no row was found
   */
  public boolean findRow(Column columnPattern, Object valuePattern)
    throws IOException
  {
    Position curPos = _curPos;
    Position prevPos = _prevPos;
    boolean found = false;
    try {
      found = findRowImpl(columnPattern, valuePattern);
      return found;
    } finally {
      if(!found) {
        try {
          restorePosition(curPos, prevPos);
        } catch(IOException e) {
          LOG.error("Failed restoring position", e);
        }
      }
    }
  }
  
  /**
   * Moves to the first row (as defined by the cursor) where the given columns
   * have the given values.  This may be more efficient on some cursors than
   * others.  If a match is not found (or an exception is thrown), the cursor
   * is restored to its previous state.
   *
   * @param rowPattern column names and values which must be equal to the
   *                   corresponding values in the matched row
   * @return {@code true} if a valid row was found with the given values,
   *         {@code false} if no row was found
   */
  public boolean findRow(Map<String,Object> rowPattern)
    throws IOException
  {
    Position curPos = _curPos;
    Position prevPos = _prevPos;
    boolean found = false;
    try {
      found = findRowImpl(rowPattern);
      return found;
    } finally {
      if(!found) {
        try {
          restorePosition(curPos, prevPos);
        } catch(IOException e) {
          LOG.error("Failed restoring position", e);
        }
      }
    }
  }
  
  /**
   * Moves to the first row (as defined by the cursor) where the given column
   * has the given value.  Caller manages save/restore on failure.
   * <p>
   * Default implementation scans the table from beginning to end.
   *
   * @param columnPattern column from the table for this cursor which is being
   *                      matched by the valuePattern
   * @param valuePattern value which is equal to the corresponding value in
   *                     the matched row
   * @return {@code true} if a valid row was found with the given value,
   *         {@code false} if no row was found
   */
  protected boolean findRowImpl(Column columnPattern, Object valuePattern)
    throws IOException
  {
    beforeFirst();
    while(moveToNextRow()) {
      if(ObjectUtils.equals(valuePattern, getCurrentRowValue(columnPattern)))
      {
        return true;
      }
    }
    return false;
  }

  /**
   * Moves to the first row (as defined by the cursor) where the given columns
   * have the given values.  Caller manages save/restore on failure.
   * <p>
   * Default implementation scans the table from beginning to end.
   *
   * @param rowPattern column names and values which must be equal to the
   *                   corresponding values in the matched row
   * @return {@code true} if a valid row was found with the given values,
   *         {@code false} if no row was found
   */
  protected boolean findRowImpl(Map<String,Object> rowPattern)
    throws IOException
  {
    beforeFirst();
    Collection<String> columnNames = rowPattern.keySet();
    while(moveToNextRow()) {
      if(ObjectUtils.equals(rowPattern, getCurrentRow(columnNames))) {
        return true;
      }
    }
    return false;
  }  

  /**
   * Moves forward as many rows as possible up to the given number of rows.
   * @return the number of rows moved.
   */
  public int moveNextRows(int numRows)
    throws IOException
  {
    return moveSomeRows(numRows, true);
  }

  /**
   * Moves backward as many rows as possible up to the given number of rows.
   * @return the number of rows moved.
   */
  public int movePreviousRows(int numRows)
    throws IOException
  {
    return moveSomeRows(numRows, false);
  }

  /**
   * Moves as many rows as possible in the given direction up to the given
   * number of rows.
   * @return the number of rows moved.
   */
  private int moveSomeRows(int numRows, boolean moveForward)
    throws IOException
  {
    int numMovedRows = 0;
    while((numMovedRows < numRows) && moveToAnotherRow(moveForward)) {
      ++numMovedRows;
    }
    return numMovedRows;
  }

  /**
   * Returns the current row in this cursor (Column name -> Column value).
   */
  public Map<String, Object> getCurrentRow()
    throws IOException
  {
    return getCurrentRow(null);
  }

  /**
   * Returns the current row in this cursor (Column name -> Column value).
   * @param columnNames Only column names in this collection will be returned
   */
  public Map<String, Object> getCurrentRow(Collection<String> columnNames)
    throws IOException
  {
    return _table.getRow(_rowState, _curPos.getRowId(), columnNames);
  }

  /**
   * Returns the given column from the current row.
   */
  public Object getCurrentRowValue(Column column)
    throws IOException
  {
    return _table.getRowValue(_rowState, _curPos.getRowId(), column);
  }

  /**
   * Returns {@code true} if this cursor is up-to-date with respect to the
   * relevant table and related table objects, {@code false} otherwise.
   */
  protected boolean isUpToDate() {
    return _rowState.isUpToDate();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + " CurPosition " + _curPos +
      ", PrevPosition " + _prevPos;
  }
    
  /**
   * Finds the next non-deleted row after the given row (as defined by this
   * cursor) and returns the id of the row, where "next" may be backwards if
   * moveForward is {@code false}.  If there are no more rows, the returned
   * rowId should equal the value returned by {@link #getLastPosition} if moving
   * forward and {@link #getFirstPosition} if moving backward.
   */
  protected abstract Position findAnotherPosition(RowState rowState,
                                                  Position curPos,
                                                  boolean moveForward)
    throws IOException;

  /**
   * Returns the DirHandler for the given movement direction.
   */
  protected abstract DirHandler getDirHandler(boolean moveForward);

  /**
   * Row iterator for this table, supports modification.
   */
  private final class RowIterator implements Iterator<Map<String, Object>>
  {
    private final Collection<String> _columnNames;
    private final boolean _moveForward;
    private boolean _hasNext = false;
    
    private RowIterator(Collection<String> columnNames, boolean moveForward)
    {
      try {
        _columnNames = columnNames;
        _moveForward = moveForward;
        reset(_moveForward);
        _hasNext = moveToAnotherRow(_moveForward);
      } catch(IOException e) {
        throw new IllegalStateException(e);
      }
    }

    public boolean hasNext() { return _hasNext; }

    public void remove() {
      throw new UnsupportedOperationException();
    }
    
    public Map<String, Object> next() {
      if(!hasNext()) {
        throw new NoSuchElementException();
      }
      try {
        Map<String, Object> rtn = getCurrentRow(_columnNames);
        _hasNext = moveToAnotherRow(_moveForward);
        return rtn;
      } catch(IOException e) {
        throw new IllegalStateException(e);
      }
    }
    
  }

  /**
   * Handles moving the cursor in a given direction.  Separates cursor
   * logic from value storage.
   */
  protected abstract class DirHandler
  {
    public abstract Position getBeginningPosition();
    public abstract Position getEndPosition();
  }

  
  /**
   * Simple un-indexed cursor.
   */
  private static final class TableScanCursor extends Cursor
  {
    /** ScanDirHandler for forward traversal */
    private final ScanDirHandler _forwardDirHandler =
      new ForwardScanDirHandler();
    /** ScanDirHandler for backward traversal */
    private final ScanDirHandler _reverseDirHandler =
      new ReverseScanDirHandler();
    /** Cursor over the pages that this table owns */
    private final UsageMap.PageCursor _ownedPagesCursor;
    
    private TableScanCursor(Table table) {
      super(new Id(table, null), table,
            FIRST_SCAN_POSITION, LAST_SCAN_POSITION);
      _ownedPagesCursor = table.getOwnedPagesCursor();
    }

    @Override
    protected ScanDirHandler getDirHandler(boolean moveForward) {
      return (moveForward ? _forwardDirHandler : _reverseDirHandler);
    }

    @Override
    protected boolean isUpToDate() {
      return(super.isUpToDate() && _ownedPagesCursor.isUpToDate());
    }
    
    @Override
    protected void reset(boolean moveForward) {
      _ownedPagesCursor.reset(moveForward);
      super.reset(moveForward);
    }

    @Override
    protected void restorePositionImpl(Position curPos, Position prevPos)
      throws IOException
    {
      if(!(curPos instanceof ScanPosition) ||
         !(prevPos instanceof ScanPosition)) {
        throw new IllegalArgumentException(
            "Restored positions must be scan positions");
      }
      _ownedPagesCursor.restorePosition(curPos.getRowId().getPageNumber(),
                                        prevPos.getRowId().getPageNumber());
      super.restorePositionImpl(curPos, prevPos);
    }

    @Override
    protected Position findAnotherPosition(RowState rowState, Position curPos,
                                           boolean moveForward)
      throws IOException
    {
      ScanDirHandler handler = getDirHandler(moveForward);
      
      // figure out how many rows are left on this page so we can find the
      // next row
      RowId curRowId = curPos.getRowId();
      Table.positionAtRowHeader(rowState, curRowId);
      int currentRowNumber = curRowId.getRowNumber();
    
      // loop until we find the next valid row or run out of pages
      while(true) {

        currentRowNumber = handler.getAnotherRowNumber(currentRowNumber);
        curRowId = new RowId(curRowId.getPageNumber(), currentRowNumber);
        Table.positionAtRowHeader(rowState, curRowId);
        
        if(!rowState.isValid()) {
          
          // load next page
          curRowId = new RowId(handler.getAnotherPageNumber(),
                               RowId.INVALID_ROW_NUMBER);
          Table.positionAtRowHeader(rowState, curRowId);
          
          if(!rowState.isHeaderPageNumberValid()) {
            //No more owned pages.  No more rows.
            return handler.getEndPosition();
          }

          // update row count and initial row number
          currentRowNumber = handler.getInitialRowNumber(
              rowState.getRowsOnHeaderPage());

        } else if(!rowState.isDeleted()) {
          
          // we found a valid, non-deleted row, return it
          return new ScanPosition(curRowId);
        }
        
      }
    }

    /**
     * Handles moving the table scan cursor in a given direction.  Separates
     * cursor logic from value storage.
     */
    private abstract class ScanDirHandler extends DirHandler {
      public abstract int getAnotherRowNumber(int curRowNumber);
      public abstract int getAnotherPageNumber();
      public abstract int getInitialRowNumber(int rowsOnPage);
    }
    
    /**
     * Handles moving the table scan cursor forward.
     */
    private final class ForwardScanDirHandler extends ScanDirHandler {
      @Override
      public Position getBeginningPosition() {
        return getFirstPosition();
      }
      @Override
      public Position getEndPosition() {
        return getLastPosition();
      }
      @Override
      public int getAnotherRowNumber(int curRowNumber) {
        return curRowNumber + 1;
      }
      @Override
      public int getAnotherPageNumber() {
        return _ownedPagesCursor.getNextPage();
      }
      @Override
      public int getInitialRowNumber(int rowsOnPage) {
        return -1;
      }
    }
    
    /**
     * Handles moving the table scan cursor backward.
     */
    private final class ReverseScanDirHandler extends ScanDirHandler {
      @Override
      public Position getBeginningPosition() {
        return getLastPosition();
      }
      @Override
      public Position getEndPosition() {
        return getFirstPosition();
      }
      @Override
      public int getAnotherRowNumber(int curRowNumber) {
        return curRowNumber - 1;
      }
      @Override
      public int getAnotherPageNumber() {
        return _ownedPagesCursor.getPreviousPage();
      }
      @Override
      public int getInitialRowNumber(int rowsOnPage) {
        return rowsOnPage;
      }
    }
    
  }

  /**
   * Indexed cursor.
   */
  private static final class IndexCursor extends Cursor
  {
    /** IndexDirHandler for forward traversal */
    private final IndexDirHandler _forwardDirHandler =
      new ForwardIndexDirHandler();
    /** IndexDirHandler for backward traversal */
    private final IndexDirHandler _reverseDirHandler =
      new ReverseIndexDirHandler();
    /** Cursor over the entries of the relvant index */
    private final Index.EntryCursor _entryCursor;

    private IndexCursor(Table table, Index index,
                        Index.EntryCursor entryCursor)
      throws IOException
    {
      super(new Id(table, index), table,
            new IndexPosition(entryCursor.getFirstEntry()),
            new IndexPosition(entryCursor.getLastEntry()));
      _entryCursor = entryCursor;
    }

    @Override
    public Index getIndex() {
      return _entryCursor.getIndex();
    }
    
    @Override
    protected IndexDirHandler getDirHandler(boolean moveForward) {
      return (moveForward ? _forwardDirHandler : _reverseDirHandler);
    }
    
    @Override
    protected boolean isUpToDate() {
      return(super.isUpToDate() && _entryCursor.isUpToDate());
    }
    
    @Override
    protected void reset(boolean moveForward) {
      _entryCursor.reset(moveForward);
      super.reset(moveForward);
    }

    @Override
    protected void restorePositionImpl(Position curPos, Position prevPos)
      throws IOException
    {
      if(!(curPos instanceof IndexPosition) ||
         !(prevPos instanceof IndexPosition)) {
        throw new IllegalArgumentException(
            "Restored positions must be index positions");
      }
      _entryCursor.restorePosition(((IndexPosition)curPos).getEntry(),
                                   ((IndexPosition)prevPos).getEntry());
      super.restorePositionImpl(curPos, prevPos);
    }

    @Override
    protected boolean findRowImpl(Column columnPattern, Object valuePattern)
      throws IOException
    {
      Object[] rowValues = _entryCursor.getIndex().constructIndexRow(
          columnPattern.getName(), valuePattern);

      if(rowValues == null) {
        // bummer, use the default table scan
        return super.findRowImpl(columnPattern, valuePattern);
      }
      
      // sweet, we can use our index
      _entryCursor.beforeEntry(rowValues);
      Index.Entry startEntry = _entryCursor.getNextEntry();
      if(!startEntry.getRowId().isValid()) {
        // at end of index, no potential matches
        return false;
      }

      // either we found a row with the given value, or none exist in the
      // table
      restorePosition(new IndexPosition(startEntry));
      return ObjectUtils.equals(getCurrentRowValue(columnPattern),
                                valuePattern);
    }

    @Override
    protected boolean findRowImpl(Map<String,Object> rowPattern)
      throws IOException
    {
      Index index = _entryCursor.getIndex();
      Object[] rowValues = index.constructIndexRow(rowPattern);

      if(rowValues == null) {
        // bummer, use the default table scan
        return super.findRowImpl(rowPattern);
      }
      
      // sweet, we can use our index
      _entryCursor.beforeEntry(rowValues);
      Index.Entry startEntry = _entryCursor.getNextEntry();
      if(!startEntry.getRowId().isValid()) {
        // at end of index, no potential matches
        return false;
      }
      restorePosition(new IndexPosition(startEntry));

      Map<String,Object> indexRowPattern =
        new LinkedHashMap<String,Object>();
      for(Index.ColumnDescriptor idxCol : _entryCursor.getIndex().getColumns())
      {
        indexRowPattern.put(idxCol.getName(),
                            rowValues[idxCol.getColumnNumber()]);
      }
        
      // there may be multiple columns which fit the pattern subset used by
      // the index, so we need to keep checking until we no longer our index
      // values no longer match
      do {

        if(!ObjectUtils.equals(getCurrentRow(indexRowPattern.keySet()),
                               indexRowPattern)) {
          // there are no more rows which could possibly match
          break;
        }

        if(ObjectUtils.equals(getCurrentRow(rowPattern.keySet()),
                              rowPattern)) {
          // found it!
          return true;
        }

      } while(moveToNextRow());
        
      // none of the potential rows matched
      return false;
    }
    
    @Override
    protected Position findAnotherPosition(RowState rowState, Position curPos,
                                           boolean moveForward)
      throws IOException
    {
      IndexDirHandler handler = getDirHandler(moveForward);
      IndexPosition endPos = (IndexPosition)handler.getEndPosition();
      Index.Entry entry = handler.getAnotherEntry();
      return ((!entry.equals(endPos.getEntry())) ?
              new IndexPosition(entry) : endPos);
    }

    /**
     * Handles moving the table index cursor in a given direction.  Separates
     * cursor logic from value storage.
     */
    private abstract class IndexDirHandler extends DirHandler {
      public abstract Index.Entry getAnotherEntry();
    }
    
    /**
     * Handles moving the table index cursor forward.
     */
    private final class ForwardIndexDirHandler extends IndexDirHandler {
      @Override
      public Position getBeginningPosition() {
        return getFirstPosition();
      }
      @Override
      public Position getEndPosition() {
        return getLastPosition();
      }
      @Override
      public Index.Entry getAnotherEntry() {
        return _entryCursor.getNextEntry();
      }
    }
    
    /**
     * Handles moving the table index cursor backward.
     */
    private final class ReverseIndexDirHandler extends IndexDirHandler {
      @Override
      public Position getBeginningPosition() {
        return getLastPosition();
      }
      @Override
      public Position getEndPosition() {
        return getFirstPosition();
      }
      @Override
      public Index.Entry getAnotherEntry() {
        return _entryCursor.getPreviousEntry();
      }
    }    
    
  }

  /**
   * Identifier for a cursor.  Will be equal to any other cursor of the same
   * type for the same table.  Primarily used to check the validity of a
   * Savepoint.
   */
  public static final class Id
  {
    private final String _tableName;
    private final String _indexName;

    private Id(Table table, Index index) {
      _tableName = table.getName();
      _indexName = ((index != null) ? index.getName() : null);
    }

    @Override
    public int hashCode() {
      return _tableName.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return((this == o) ||
             ((o != null) && (getClass() == o.getClass()) &&
              ObjectUtils.equals(_tableName, ((Id)o)._tableName) &&
              ObjectUtils.equals(_indexName, ((Id)o)._indexName)));
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + " " + _tableName + ":" + _indexName;
    }
  }

  /**
   * Value object which represents a complete save state of the cursor.
   */
  public static final class Savepoint
  {
    private final Id _cursorId;
    private final Position _curPos;
    private final Position _prevPos;

    private Savepoint(Id cursorId, Position curPos, Position prevPos) {
      _cursorId = cursorId;
      _curPos = curPos;
      _prevPos = prevPos;
    }

    public Id getCursorId() {
      return _cursorId;
    }

    public Position getCurrentPosition() {
      return _curPos;
    }

    private Position getPreviousPosition() {
      return _prevPos;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + " " + _cursorId + " CurPosition " + 
        _curPos + ", PrevPosition " + _prevPos;
    }
  }
  
  /**
   * Value object which maintains the current position of the cursor.
   */
  public static abstract class Position
  {
    protected Position() {
    }

    @Override
    public final int hashCode() {
      return getRowId().hashCode();
    }
    
    @Override
    public final boolean equals(Object o) {
      return((this == o) ||
             ((o != null) && (getClass() == o.getClass()) && equalsImpl(o)));
    }

    /**
     * Returns the unique RowId of the position of the cursor.
     */
    public abstract RowId getRowId();

    /**
     * Returns {@code true} if the subclass specific info in a Position is
     * equal, {@code false} otherwise.
     * @param o object being tested for equality, guaranteed to be the same
     *          class as this object
     */
    protected abstract boolean equalsImpl(Object o);
  }

  /**
   * Value object which maintains the current position of a TableScanCursor.
   */
  private static final class ScanPosition extends Position
  {
    private final RowId _rowId;

    private ScanPosition(RowId rowId) {
      _rowId = rowId;
    }

    @Override
    public RowId getRowId() {
      return _rowId;
    }

    @Override
    protected boolean equalsImpl(Object o) {
      return getRowId().equals(((ScanPosition)o).getRowId());
    }
    
    @Override
    public String toString() {
      return "RowId = " + getRowId();
    }
  }
  
  /**
   * Value object which maintains the current position of an IndexCursor.
   */
  private static final class IndexPosition extends Position
  {
    private final Index.Entry _entry;
    
    private IndexPosition(Index.Entry entry) {
      _entry = entry;
    }

    @Override
    public RowId getRowId() {
      return getEntry().getRowId();
    }
    
    public Index.Entry getEntry() {
      return _entry;
    }
    
    @Override
    protected boolean equalsImpl(Object o) {
      return getEntry().equals(((IndexPosition)o).getEntry());
    }

    @Override
    public String toString() {
      return "Entry = " + getEntry();
    }
  }
  
}
