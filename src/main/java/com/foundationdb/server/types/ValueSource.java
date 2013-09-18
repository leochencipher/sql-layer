/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.types;

import com.foundationdb.qp.operator.Cursor;
import com.foundationdb.qp.operator.StoreAdapter;
import com.foundationdb.server.Quote;
import com.foundationdb.server.collation.AkCollator;
import com.foundationdb.util.AkibanAppender;
import com.foundationdb.util.ByteSource;

import java.math.BigDecimal;
import java.math.BigInteger;

@Deprecated
public abstract class ValueSource {

    public abstract boolean isNull();

    public abstract BigDecimal getDecimal();

    public abstract BigInteger getUBigInt();

    public abstract ByteSource getVarBinary();

    public abstract double getDouble();

    public abstract double getUDouble();

    public abstract float getFloat();

    public abstract float getUFloat();

    public abstract long getDate();

    public abstract long getDateTime();

    public abstract long getInt();

    public abstract long getLong();

    public abstract long getTime();

    public abstract long getTimestamp();

    public abstract long getInterval_Millis();

    public abstract long getInterval_Month();

    public abstract long getUInt();

    public abstract long getYear();

    public abstract String getString();

    public abstract String getText();

    public abstract boolean getBool();

    public abstract Cursor getResultSet();

    public abstract void appendAsString(AkibanAppender appender, Quote quote);

    public abstract AkType getConversionType();

    public long hash(StoreAdapter adapter, AkCollator collator)
    {
        return 0L;
    }
}