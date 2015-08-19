/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.yarn.server.timelineservice.storage.common;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.yarn.server.timeline.GenericObjectMapper;

/**
 * This class is meant to be used only by explicit Columns, and not directly to
 * write by clients.
 *
 * @param <T> refers to the table.
 */
public class ColumnHelper<T> {
  private static final Log LOG = LogFactory.getLog(ColumnHelper.class);

  private final ColumnFamily<T> columnFamily;

  /**
   * Local copy of bytes representation of columnFamily so that we can avoid
   * cloning a new copy over and over.
   */
  private final byte[] columnFamilyBytes;

  public ColumnHelper(ColumnFamily<T> columnFamily) {
    this.columnFamily = columnFamily;
    columnFamilyBytes = columnFamily.getBytes();
  }

  /**
   * Sends a Mutation to the table. The mutations will be buffered and sent over
   * the wire as part of a batch.
   *
   * @param rowKey identifying the row to write. Nothing gets written when null.
   * @param tableMutator used to modify the underlying HBase table
   * @param columnQualifier column qualifier. Nothing gets written when null.
   * @param timestamp version timestamp. When null the server timestamp will be
   *          used.
   * @param inputValue the value to write to the rowKey and column qualifier.
   *          Nothing gets written when null.
   * @throws IOException
   */
  public void store(byte[] rowKey, TypedBufferedMutator<?> tableMutator,
      byte[] columnQualifier, Long timestamp, Object inputValue)
      throws IOException {
    if ((rowKey == null) || (columnQualifier == null) || (inputValue == null)) {
      return;
    }
    Put p = new Put(rowKey);

    if (timestamp == null) {
      p.addColumn(columnFamilyBytes, columnQualifier,
          GenericObjectMapper.write(inputValue));
    } else {
      p.addColumn(columnFamilyBytes, columnQualifier, timestamp,
          GenericObjectMapper.write(inputValue));
    }
    tableMutator.mutate(p);
  }

  /**
   * @return the column family for this column implementation.
   */
  public ColumnFamily<T> getColumnFamily() {
    return columnFamily;
  }

  /**
   * Get the latest version of this specified column. Note: this call clones the
   * value content of the hosting {@link Cell}.
   *
   * @param result from which to read the value. Cannot be null
   * @param columnQualifierBytes referring to the column to be read.
   * @return latest version of the specified column of whichever object was
   *         written.
   * @throws IOException
   */
  public Object readResult(Result result, byte[] columnQualifierBytes)
      throws IOException {
    if (result == null || columnQualifierBytes == null) {
      return null;
    }

    // Would have preferred to be able to use getValueAsByteBuffer and get a
    // ByteBuffer to avoid copy, but GenericObjectMapper doesn't seem to like
    // that.
    byte[] value = result.getValue(columnFamilyBytes, columnQualifierBytes);
    return GenericObjectMapper.read(value);
  }

  /**
   * @param result from which to reads data with timestamps
   * @param columnPrefixBytes optional prefix to limit columns. If null all
   *          columns are returned.
   * @param <V> the type of the values. The values will be cast into that type.
   * @return the cell values at each respective time in for form
   *         {idA={timestamp1->value1}, idA={timestamp2->value2},
   *         idB={timestamp3->value3}, idC={timestamp1->value4}}
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  public <V> NavigableMap<String, NavigableMap<Long, V>>
      readResultsWithTimestamps(Result result, byte[] columnPrefixBytes)
          throws IOException {

    NavigableMap<String, NavigableMap<Long, V>> results =
        new TreeMap<String, NavigableMap<Long, V>>();

    if (result != null) {
      NavigableMap<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> resultMap =
          result.getMap();

      NavigableMap<byte[], NavigableMap<Long, byte[]>> columnCellMap =
          resultMap.get(columnFamilyBytes);

      // could be that there is no such column family.
      if (columnCellMap != null) {
        for (Entry<byte[], NavigableMap<Long, byte[]>> entry : columnCellMap
            .entrySet()) {
          String columnName = null;
          if (columnPrefixBytes == null) {
            LOG.info("null prefix was specified; returning all columns");
            // Decode the spaces we encoded in the column name.
            columnName = Separator.decode(entry.getKey(), Separator.SPACE);
          } else {
            // A non-null prefix means columns are actually of the form
            // prefix!columnNameRemainder
            byte[][] columnNameParts =
                Separator.QUALIFIERS.split(entry.getKey(), 2);
            byte[] actualColumnPrefixBytes = columnNameParts[0];
            if (Bytes.equals(columnPrefixBytes, actualColumnPrefixBytes)
                && columnNameParts.length == 2) {
              // This is the prefix that we want
              columnName = Separator.decode(columnNameParts[1]);
            }
          }

          // If this column has the prefix we want
          if (columnName != null) {
            NavigableMap<Long, V> cellResults =
                new TreeMap<Long, V>();
            NavigableMap<Long, byte[]> cells = entry.getValue();
            if (cells != null) {
              for (Entry<Long, byte[]> cell : cells.entrySet()) {
                V value =
                    (V) GenericObjectMapper.read(cell.getValue());
                cellResults.put(cell.getKey(), value);
              }
            }
            results.put(columnName, cellResults);
          }
        } // for entry : columnCellMap
      } // if columnCellMap != null
    } // if result != null
    return results;
  }

  /**
   * @param result from which to read columns
   * @param columnPrefixBytes optional prefix to limit columns. If null all
   *        columns are returned.
   * @return the latest values of columns in the column family. This assumes
   *         that the column name parts are all Strings by default. If the
   *         column name parts should be treated natively and not be converted
   *         back and forth from Strings, you should use
   *         {@link #readResultsHavingCompoundColumnQualifiers(Result, byte[])}
   *         instead.
   * @throws IOException
   */
  public Map<String, Object> readResults(Result result,
      byte[] columnPrefixBytes) throws IOException {
    Map<String, Object> results = new HashMap<String, Object>();

    if (result != null) {
      Map<byte[], byte[]> columns = result.getFamilyMap(columnFamilyBytes);
      for (Entry<byte[], byte[]> entry : columns.entrySet()) {
        byte[] columnKey = entry.getKey();
        if (columnKey != null && columnKey.length > 0) {

          String columnName = null;
          if (columnPrefixBytes == null) {
            LOG.info("null prefix was specified; returning all columns");
            // Decode the spaces we encoded in the column name.
            columnName = Separator.decode(columnKey, Separator.SPACE);
          } else {
            // A non-null prefix means columns are actually of the form
            // prefix!columnNameRemainder
            byte[][] columnNameParts =
                Separator.QUALIFIERS.split(columnKey, 2);
            byte[] actualColumnPrefixBytes = columnNameParts[0];
            if (Bytes.equals(columnPrefixBytes, actualColumnPrefixBytes)
                && columnNameParts.length == 2) {
              // This is the prefix that we want
              // if the column name is a compound qualifier
              // with non string datatypes, the following decode will not
              // work correctly since it considers all components to be String
              // invoke the readResultsHavingCompoundColumnQualifiers function
              columnName = Separator.decode(columnNameParts[1]);
            }
          }

          // If this column has the prefix we want
          if (columnName != null) {
            Object value = GenericObjectMapper.read(entry.getValue());
            results.put(columnName, value);
          }
        }
      } // for entry
    }
    return results;
  }

  /**
   * @param result from which to read columns
   * @param columnPrefixBytes optional prefix to limit columns. If null all
   *        columns are returned.
   * @return the latest values of columns in the column family. If the column
   *         prefix is null, the column qualifier is returned as Strings. For a
   *         non-null column prefix bytes, the column qualifier is returned as
   *         a list of parts, each part a byte[]. This is to facilitate
   *         returning byte arrays of values that were not Strings.
   * @throws IOException
   */
  public Map<?, Object> readResultsHavingCompoundColumnQualifiers(Result result,
      byte[] columnPrefixBytes) throws IOException {
    // handle the case where the column prefix is null
    // it is the same as readResults() so simply delegate to that implementation
    if (columnPrefixBytes == null) {
      return readResults(result, null);
    }

    Map<byte[][], Object> results = new HashMap<byte[][], Object>();

    if (result != null) {
      Map<byte[], byte[]> columns = result.getFamilyMap(columnFamilyBytes);
      for (Entry<byte[], byte[]> entry : columns.entrySet()) {
        byte[] columnKey = entry.getKey();
        if (columnKey != null && columnKey.length > 0) {
          // A non-null prefix means columns are actually of the form
          // prefix!columnNameRemainder
          // with a compound column qualifier, we are presuming existence of a
          // prefix
          byte[][] columnNameParts = Separator.QUALIFIERS.split(columnKey, 2);
          if (columnNameParts.length > 0) {
            byte[] actualColumnPrefixBytes = columnNameParts[0];
            if (Bytes.equals(columnPrefixBytes, actualColumnPrefixBytes)
                && columnNameParts.length == 2) {
              // This is the prefix that we want
              byte[][] columnQualifierParts =
                  Separator.VALUES.split(columnNameParts[1]);
              Object value = GenericObjectMapper.read(entry.getValue());
              // we return the columnQualifier in parts since we don't know
              // which part is of which data type
              results.put(columnQualifierParts, value);
            }
          }
        }
      } // for entry
    }
    return results;
  }

  /**
   * @param columnPrefixBytes The byte representation for the column prefix.
   *          Should not contain {@link Separator#QUALIFIERS}.
   * @param qualifier for the remainder of the column. Any
   *          {@link Separator#QUALIFIERS} will be encoded in the qualifier.
   * @return fully sanitized column qualifier that is a combination of prefix
   *         and qualifier. If prefix is null, the result is simply the encoded
   *         qualifier without any separator.
   */
  public static byte[] getColumnQualifier(byte[] columnPrefixBytes,
      String qualifier) {

    // We don't want column names to have spaces
    byte[] encodedQualifier = Bytes.toBytes(Separator.SPACE.encode(qualifier));
    if (columnPrefixBytes == null) {
      return encodedQualifier;
    }

    // Convert qualifier to lower case, strip of separators and tag on column
    // prefix.
    byte[] columnQualifier =
        Separator.QUALIFIERS.join(columnPrefixBytes, encodedQualifier);
    return columnQualifier;
  }

  /**
   * @param columnPrefixBytes The byte representation for the column prefix.
   *          Should not contain {@link Separator#QUALIFIERS}.
   * @param qualifier the byte representation for the remainder of the column.
   * @return fully sanitized column qualifier that is a combination of prefix
   *         and qualifier. If prefix is null, the result is simply the encoded
   *         qualifier without any separator.
   */
  public static byte[] getColumnQualifier(byte[] columnPrefixBytes,
      byte[] qualifier) {

    if (columnPrefixBytes == null) {
      return qualifier;
    }

    byte[] columnQualifier =
        Separator.QUALIFIERS.join(columnPrefixBytes, qualifier);
    return columnQualifier;
  }

}
