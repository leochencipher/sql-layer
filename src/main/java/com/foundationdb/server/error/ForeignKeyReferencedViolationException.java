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

package com.foundationdb.server.error;

import com.foundationdb.ais.model.TableName;

public final class ForeignKeyReferencedViolationException extends InvalidOperationException {
    public ForeignKeyReferencedViolationException(String action, TableName referencedTableName, String key, String constraintName, TableName referencingTableName) {
        super(ErrorCode.FK_REFERENCED_VIOLATION, action, referencedTableName.getSchemaName(), referencedTableName.getTableName(), key, constraintName, referencingTableName.getSchemaName(), referencingTableName.getTableName());
    }
}
