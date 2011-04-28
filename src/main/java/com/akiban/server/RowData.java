/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.BitSet;

import com.akiban.server.encoding.EncodingException;
import com.akiban.server.util.RowDefNotFoundException;
import com.akiban.util.AkibanAppender;
import com.persistit.Key;

/**
 * Represent one or more rows of table data. The backing store is a byte array
 * supplied in the constructor. The {@link #RowData(byte[], int, int)}
 * constructor allows use of a partially filled reusable buffer.
 * 
 * This class provides methods for both interpreting and constructing row data
 * structures in the byte array. After a call to {@link #reset(int, int)} the
 * index will point to the first field of the first row represented in the
 * buffer.
 * 
 * <pre>
 *   +0: record length (Z) 
 *   +4: signature bytes, e.g., 'AB' 
 *   +6: field count (short) 
 *   +8: rowDefId (int) 
 *  +12: null-map (1 bit per schema-defined column)
 *   +M: fixed-length field 
 *   +N: fixed-length field ... 
 *   +Q: variable-length field
 *   +R: variable-length field ... 
 *   +Z-6: signature bytes: e.g., 'BA' 
 *   +Z-4: record length (Z)
 * </pre>
 * 
 * @author peter
 */
public class RowData {

    public final static int O_LENGTH_A = 0;

    public final static int O_SIGNATURE_A = 4;

    public final static int O_FIELD_COUNT = 6;

    public final static int O_ROW_DEF_ID = 8;

    public final static int O_NULL_MAP = 12;

    public final static int O_SIGNATURE_B = -6;

    public final static int O_LENGTH_B = -4;

    public final static int MINIMUM_RECORD_LENGTH = 18;

    // Arbitrary sanity bound on maximum size
    // Needs to be about the same size as the smaller of:
    // com.akiban.network.AkibanCommPipelineFactory#MAX_PACKET_SIZE
    // com.akiban.message.NettyAkibanConnectionImpl#MAX_PACKET_SIZE
    // These two limit the network throughput message size.
    public final static int MAXIMUM_RECORD_LENGTH = 8 * 1024 * 1024;

    public final static char SIGNATURE_A = (char) ('A' + ('B' << 8));

    public final static char SIGNATURE_B = (char) ('B' + ('A' << 8));

    public final static int ENVELOPE_SIZE = 12;

    public final static int LEFT_ENVELOPE_SIZE = 6;

    public final static int RIGHT_ENVELOPE_SIZE = 6;

    public final static int CREATE_ROW_INITIAL_SIZE = 500;

    private final static SimpleDateFormat SDF_DATETIME = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:SS");

    private final static SimpleDateFormat SDF_TIME = new SimpleDateFormat(
            "HH:mm:SS");

    private byte[] bytes;

    private int bufferStart;

    private int bufferEnd;

    private int rowStart;
    private int rowEnd;

    private Key hKey;

    // In an hkey-ordered sequence of RowData objects, adjacent hkeys indicate how the corresponding RowDatas
    // relate to one another -- the second one can be a child of the first, a descendent, have a common ancestor,
    // etc. The essential information is captured by differsFromPredecessorAtKeySegment, which identifies the
    // hkey segment number at which this RowData's hkey differed from that of the previous RowData.
    // This field is declared transient to indicate that this value is not copied into a message carrying a
    // RowData.
    private transient int differsFromPredecessorAtKeySegment = -1;

    public RowData() {

    }

    public RowData(final byte[] bytes) {
        this.bytes = bytes;
        reset(0, bytes.length);
    }

    public RowData(final byte[] bytes, final int offset, final int length) {
        this.bytes = bytes;
        reset(offset, length);
    }

    public void reset(final int offset, final int length) {
        this.bufferStart = offset;
        this.bufferEnd = offset + length;
        rowStart = rowEnd = bufferStart;
    }

    public void reset(final byte[] bytes) {
        this.bytes = bytes;
        reset(0, bytes.length);
    }

    public void reset(final byte[] bytes, final int offset, final int length) {
        this.bytes = bytes;
        reset(offset, length);
    }

    /**
     * Interpret the length and signature fixed fields of the row at the
     * specified offset. This method sets the {@link #rowStart} and {@link #rowEnd}
     * fields so that subsequent calls to interpret fields are supported.
     * 
     * @param offset
     *            byte offset to record start within the buffer
     */
    public boolean prepareRow(final int offset) throws CorruptRowDataException {
        if (offset == bufferEnd) {
            return false;
        }
        if (offset < 0 || offset + MINIMUM_RECORD_LENGTH > bufferEnd) {
            throw new CorruptRowDataException("Invalid offset: " + offset);
        } else {
            validateRow(offset);
            rowStart = offset;
            rowEnd = offset + AkServerUtil.getInt(bytes, O_LENGTH_A + offset);
            return true;
        }
    }

    public void validateRow(final int offset) throws CorruptRowDataException {

        if (offset < 0 || offset + MINIMUM_RECORD_LENGTH > bufferEnd) {
            throw new CorruptRowDataException("Invalid offset: " + offset);
        } else {
            final int recordLength = AkServerUtil.getInt(bytes, O_LENGTH_A
                    + offset);
            if (recordLength < 0 || recordLength + offset > bufferEnd) {
                throw new CorruptRowDataException("Invalid record length: "
                        + recordLength + " at offset: " + offset);
            }
            if (AkServerUtil.getChar(bytes, O_SIGNATURE_A + offset) != SIGNATURE_A) {
                throw new CorruptRowDataException(
                        "Invalid signature at offset: " + offset);
            }
            final int trailingLength = AkServerUtil.getInt(bytes, offset
                    + recordLength + O_LENGTH_B);
            if (trailingLength != recordLength) {
                throw new CorruptRowDataException(
                        "Invalid trailing record length " + trailingLength
                                + " in record at offset: " + offset);
            }
            if (AkServerUtil.getChar(bytes, offset + recordLength
                    + O_SIGNATURE_B) != SIGNATURE_B) {
                throw new CorruptRowDataException(
                        "Invalid signature at offset: " + offset);
            }
        }
    }

    public boolean elide(final byte[] bits, final int field, final int width) {
        // TODO - ignore for now
        return false;
    }

    public boolean nextRow() {
        if (rowEnd < bufferEnd) {
            return prepareRow(rowEnd);
        } else {
            return false;
        }
    }

    public int getBufferStart() {
        return bufferStart;
    }

    public int getBufferLength() {
        return bufferEnd - bufferStart;
    }

    public int getBufferEnd() {
        return bufferEnd;
    }

    public int getRowStart() {
        return rowStart;
    }

    public int getRowStartData() {
        return rowStart + O_NULL_MAP + (getFieldCount() + 7) / 8;
    }

    public int getRowEnd() {
        return rowEnd;
    }

    public int getRowSize() {
        return rowEnd - rowStart;
    }

    public int getInnerStart() {
        return rowStart + O_FIELD_COUNT;
    }

    public int getInnerSize() {
        return rowEnd - rowStart + O_SIGNATURE_B - O_FIELD_COUNT;
    }

    public int getFieldCount() {
        return AkServerUtil.getChar(bytes, rowStart + O_FIELD_COUNT);
    }

    public int getRowDefId() {
        return AkServerUtil.getInt(bytes, rowStart + O_ROW_DEF_ID);
    }

    public byte[] getBytes() {
        return bytes;
    }

    public int getColumnMapByte(final int offset) {
        return bytes[offset + rowStart + O_NULL_MAP] & 0xFF;
    }

    public boolean isNull(final int fieldIndex) {
        if (fieldIndex < 0 || fieldIndex >= getFieldCount()) {
            throw new IllegalArgumentException("No such field " + fieldIndex
                    + " in " + this);
        } else {
            return (getColumnMapByte(fieldIndex / 8) & (1 << (fieldIndex % 8))) != 0;
        }
    }
    
    public boolean isAllNull() {
        final int fieldCount = getFieldCount();
        for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
            if (!isNull(fieldIndex)) {
                return false;
            }
        }
        return true;
    }

    public long getIntegerValue(final int offset, final int width) {
        if (offset < rowStart || offset + width >= rowEnd) {
            throw new IllegalArgumentException(String.format("Bad location: {offset=%d width=%d start=%d end=%d}",
                    offset, width, rowStart, rowEnd));
        }
        return AkServerUtil.getUnsignedIntegerByWidth(bytes, offset, width);
    }

    public String getStringValue(final int offset, final int width,
            final FieldDef fieldDef) {
        if (offset == 0 && width == 0) {
            return null;
        }
        if (offset < rowStart || offset + width >= rowEnd) {
            throw new IllegalArgumentException("Bad location: " + offset + ":"
                    + width);
        }
        return AkServerUtil.decodeMySQLString(bytes, offset, width, fieldDef);
    }

    public ByteBuffer byteBufferForStringValue(final int offset, final int width, final FieldDef fieldDef) {
        if (offset == 0 && width == 0) {
            return null;
        }
        if (offset < rowStart || offset + width >= rowEnd) {
            throw new IllegalArgumentException("Bad location: " + offset + ":"
                    + width);
        }
        return AkServerUtil.byteBufferForMySQLString(bytes, offset, width, fieldDef);
    }

    public int setupNullMap(BitSet nullMap, int nullMapOffset,
            int currentOffset, int fieldCount) {
        int offset = currentOffset + O_NULL_MAP;
        int mapSize = ((fieldCount % 8) == 0 ? fieldCount : fieldCount + 1);
        bytes[offset] = 0;

        for (int i = nullMapOffset, j = 0; j < mapSize; i++, j++) {
            if (nullMap.get(i)) {
                // assert false;
                bytes[offset] |= 1 << (j % 8);
            }
            if ((j + 1) % 8 == 0) {
                offset++;
                bytes[offset] = 0;
            }
        }
        offset++;
        return offset;
    }

    public void copy(final RowDef rowDef, RowData rdata, BitSet nullMap,
            int nullMapOffset) throws IOException {
        final int fieldCount = rowDef.getFieldCount();
        int offset = rowStart;

        AkServerUtil.putChar(bytes, offset + O_SIGNATURE_A, SIGNATURE_A);
        AkServerUtil.putInt(bytes, offset + O_ROW_DEF_ID, rowDef.getRowDefId());
        AkServerUtil.putChar(bytes, offset + O_FIELD_COUNT, fieldCount);

        offset = setupNullMap(nullMap, nullMapOffset, offset, fieldCount);
        // If the row is a projection, then the field array list is less than
        // the field count. To account for this situation the field
        // variable iterates over the columns and the position variable
        // iterates over the field array list -- James
        for (int groupOffset = nullMapOffset, field = 0, position = 0; field < fieldCount; groupOffset++, field++) {
            // System.out.println("table "+rowDef.getTableName()+"field count = "
            // +fieldCount+" position = "+position);
            if (!nullMap.get(groupOffset)) {
                assert rowDef.getFieldDef(field).isFixedSize() == true;
                long offsetWidth = rowDef.fieldLocation(rdata, field);
                System.arraycopy(rdata.getBytes(), ((int) offsetWidth), bytes,
                        offset, ((int) (offsetWidth >>> 32)));
                position++;
                offset += ((int) (offsetWidth >>> 32));
            }
        }

        AkServerUtil.putChar(bytes, offset, SIGNATURE_B);
        offset += 6;
        final int length = offset - rowStart;
        AkServerUtil.putInt(bytes, rowStart + O_LENGTH_A, length);
        AkServerUtil.putInt(bytes, offset + O_LENGTH_B, length);
        rowEnd = offset;
    }

    public RowData copy()
    {
        byte[] copyBytes = new byte[rowEnd - rowStart];
        System.arraycopy(bytes, rowStart, copyBytes, 0, rowEnd - rowStart);
        RowData copy = new RowData(copyBytes);
        copy.prepareRow(0);
        copy.differsFromPredecessorAtKeySegment = differsFromPredecessorAtKeySegment;
        if (hKey != null) {
            copy.hKey = new Key(hKey);
        }
        return copy;
    }

    public void createRow(final RowDef rowDef, final Object[] values, boolean growBuffer)
    {
        if (growBuffer && !(bufferStart == 0 && bufferEnd == bytes.length)) {
            // This RowData is embedded in a larger buffer. Can't grow it safely.
            throw new CannotGrowBufferException();
        }
        RuntimeException exception = null;
        do {
            try {
                exception = null;
                createRow(rowDef, values);
            } catch (ArrayIndexOutOfBoundsException e) {
                exception = e;
            } catch (EncodingException e) {
                if (e.getCause() instanceof ArrayIndexOutOfBoundsException) {
                    exception = e;
                } else {
                    throw e;
                }
            }
            if (exception != null && growBuffer) {
                int newSize = bytes.length == 0 ? CREATE_ROW_INITIAL_SIZE : bytes.length * 2;
                bytes = new byte[newSize];
            }
        } while (growBuffer && exception != null);
        if (exception != null) {
            throw exception;
        }
    }

    public void createRow(final RowDef rowDef, final Object[] values)
    {
        final int fieldCount = rowDef.getFieldCount();
        if (values.length > rowDef.getFieldCount()) {
            throw new IllegalArgumentException("Too many values.");
        }
        int offset = rowStart;
        AkServerUtil.putChar(bytes, offset + O_SIGNATURE_A, SIGNATURE_A);
        AkServerUtil.putInt(bytes, offset + O_ROW_DEF_ID, rowDef.getRowDefId());
        AkServerUtil.putChar(bytes, offset + O_FIELD_COUNT, fieldCount);
        offset = offset + O_NULL_MAP;
        for (int index = 0; index < fieldCount; index += 8) {
            int b = 0;
            for (int j = index; j < index + 8 && j < fieldCount; j++) {
                if (j >= values.length || values[j] == null) {
                    b |= (1 << j - index);
                }
            }
            bytes[offset++] = (byte) b;
        }
        int vlength = 0;
        int vmax = 0;
        for (int index = 0; index < values.length; index++) {
            Object object = values[index];
            FieldDef fieldDef = rowDef.getFieldDef(index);
            if (fieldDef.isFixedSize()) {
                if (object != null) {
                    try {
                        offset += fieldDef.getEncoding().fromObject(fieldDef, object, bytes, offset);
                    } catch (Exception e) {
                        throw EncodingException.dueTo(e);
                    }

                }
            } else {
                int fieldMax = fieldDef.getMaxStorageSize();
                vmax += fieldMax;
                if (object != null) {
                    int fieldWidth;
                    try {
                        fieldWidth = fieldDef.getEncoding().widthFromObject(fieldDef, object);
                        vlength += fieldWidth;
                    } catch (Exception e) {
                        throw EncodingException.dueTo(e);
                    }
                    if (fieldWidth > fieldMax) {
                        throw new EncodingException(
                            String.format("Value for field %s has size %s, exceeding maximum allowed: %s",
                                          fieldDef.column(), fieldWidth, fieldMax));
                    }
                    final int width = AkServerUtil.varWidth(vmax);
                    switch (width) {
                    case 0:
                        break;
                    case 1:
                        AkServerUtil.putByte(bytes, offset, (byte) vlength);
                        break;
                    case 2:
                        AkServerUtil.putChar(bytes, offset, (char) vlength);
                        break;
                    case 3:
                        AkServerUtil.putMediumInt(bytes, offset, (int) vlength);
                        break;
                    }
                    offset += width;
                }
            }
        }
        for (int index = 0; index < values.length; index++) {
            Object object = values[index];
            final FieldDef fieldDef = rowDef.getFieldDef(index);
            if (object != null && !fieldDef.isFixedSize()) {
                try {
                    offset += fieldDef.getEncoding().fromObject(fieldDef, values[index], bytes, offset);
                } catch (Exception e) {
                    throw EncodingException.dueTo(e);
                }
            }
        }
        AkServerUtil.putChar(bytes, offset, SIGNATURE_B);
        offset += 6;
        final int length = offset - rowStart;
        AkServerUtil.putInt(bytes, rowStart + O_LENGTH_A, length);
        AkServerUtil.putInt(bytes, offset + O_LENGTH_B, length);
        rowEnd = offset;
    }

    public void updateNonNullLong(FieldDef fieldDef, long rowId)
    {
        // Offset is in low 32 bits of fieldLocation return value
        int offset = (int) fieldDef.getRowDef().fieldLocation(this, fieldDef.getFieldIndex());
        AkServerUtil.putLong(bytes, offset, rowId);
    }

    /**
     * Debug-only: returns a hex-dump of the backing buffer.
     */
    @Override
    public String toString() {
        // return AkSserverUtil.dump(bytes, rowStart, rowEnd - rowStart);
        return toString(RowDefCache.latest());
    }

    public String toString(final RowDefCache cache) {
        final RowDef rowDef;
        if (cache == null) {
            rowDef = null;
        }
        else {
            RowDef tmp;
            try {
                tmp = cache.getRowDef(getRowDefId());
            }
            catch ( RowDefNotFoundException e) {
                tmp = null;
            }
            rowDef = tmp;
        }
        return toString(rowDef);
    }

    public String toString(final RowDef rowDef) {
        final AkibanAppender sb = AkibanAppender.of(new StringBuilder());
        try {
            if (rowDef == null) {
                sb.append("RowData?(rowDefId=");
                sb.append(getRowDefId());
                sb.append(": ");
                AkServerUtil.hex(sb, bytes, rowStart, rowEnd - rowStart);
            } else {
                sb.append(rowDef.getTableName());
                for (int i = 0; i < getFieldCount(); i++) {
                    final FieldDef fieldDef = rowDef.getFieldDef(i);
                    sb.append(i == 0 ? '(' : ',');
                    final long location = fieldDef.getRowDef().fieldLocation(
                            this, fieldDef.getFieldIndex());
                    if (location == 0) {
                       // sb.append("null");
                    } else {
                        fieldDef.getEncoding().toString(fieldDef, this, sb,
                                Quote.SINGLE_QUOTE);
                    }
                }
                sb.append(')');
            }
        } catch (Exception e) {
            int size = Math.min(getRowSize(), 64);
            if (size > 0 && rowStart >= 0) {
                sb.append(AkServerUtil.dump(bytes, rowStart, size));
            }
            return sb.toString();
        }
        return sb.toString();
    }

    public void toJSONString(final RowDef rowDef, AkibanAppender sb) throws IOException {
        for(int i = 0; i < getFieldCount(); i++) {
            final FieldDef fieldDef = rowDef.getFieldDef(i);
            final long location = fieldDef.getRowDef().fieldLocation(this, fieldDef.getFieldIndex());
            if(i != 0) {
                sb.append(',');
            }
            sb.append('"');
            String fieldName = fieldDef.getName();
            if (fieldName != null
                    && fieldName.length() > 0
                    && (fieldName.charAt(0) == '@' || fieldName.charAt(0) == ':')
            ) {
                sb.append(':');
            }
            sb.append(fieldName);
            sb.append("\":");

            if(location != 0) {
                fieldDef.getEncoding().toString(fieldDef, this, sb, Quote.JSON_QUOTE);
            }
            else {
                sb.append("null");
            }
        }
    }

    public String explain() {

        final StringBuilder sb = new StringBuilder();
        return sb.toString();
    }

    public Object toObject(RowDef rowDef, int fieldIndex)
    {
        Object object = null;
        long location = rowDef.fieldLocation(this, fieldIndex);
        if (location != 0L) {
            FieldDef field = rowDef.getFieldDef(fieldIndex);
            object = field.getEncoding().toObject(field, this);
        }
        return object;
    }

    public Object fromObject(RowDef rowDef, int fieldIndex, Object value)
    {
        // Not implemented yet
        throw new UnsupportedOperationException();
    }

    public int differsFromPredecessorAtKeySegment()
    {
        return differsFromPredecessorAtKeySegment;
    }

    public void differsFromPredecessorAtKeySegment(int differsFromPredecessorAtKeySegment)
    {
        this.differsFromPredecessorAtKeySegment = differsFromPredecessorAtKeySegment;
    }

    public Key hKey()
    {
        return hKey;
    }

    public void hKey(Key hKey)
    {
        this.hKey = hKey;
    }

    public static int nullRowBufferSize(RowDef rowDef)
    {
        return MINIMUM_RECORD_LENGTH + // header and trailer
               (rowDef.getFieldCount() + 7) / 8; // null bitmap
    }
}
