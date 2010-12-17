package com.akiban.cserver.store;

import java.nio.ByteBuffer;

import junit.framework.TestCase;

import org.junit.After;
import org.junit.Before;

import com.akiban.ais.ddl.DDLSource;
import com.akiban.ais.model.AkibaInformationSchema;
import com.akiban.cserver.CServerConstants;
import com.akiban.cserver.RowData;
import com.akiban.cserver.RowDef;
import com.akiban.cserver.RowDefCache;
import com.akiban.cserver.service.ServiceManager;
import com.akiban.cserver.service.UnitTestServiceManagerFactory;
import com.akiban.cserver.service.session.Session;
import com.akiban.cserver.service.session.SessionImpl;

public class SimpleBlobTest extends TestCase implements CServerConstants {

    private final static String CREATE_TABLE_STATEMENT1 = "CREATE TABLE `test`.`blobtest` ("
            + "`a` int,"
            + "`b` blob,"
            + "`c` blob,"
            + "PRIMARY KEY (a)"
            + ") ENGINE=AKIBANDB;";

    private Store store;
    
    private ServiceManager serviceManager;

    protected final static Session session = new SessionImpl();

    private RowDefCache rowDefCache;

    @Before
    @Override
    public void setUp() throws Exception {
        serviceManager = UnitTestServiceManagerFactory.createServiceManager();
        serviceManager.startServices();
        store = serviceManager.getStore();
        rowDefCache = store.getRowDefCache();
        final AkibaInformationSchema ais = new DDLSource().buildAISFromString(CREATE_TABLE_STATEMENT1);
        rowDefCache.setAIS(ais);
        store.fixUpOrdinals();
    }

    @After
    @Override
    public void tearDown() throws Exception {
        store.stop();
        store = null;
        rowDefCache = null;
    }
    
    public void testBlobs() throws Exception {
        final RowDef rowDef = rowDefCache.getRowDef("test.blobtest");
        final RowData rowData =new RowData(new byte[5000000]);
        final String[] expected = new String[7];
        for (int i = 1; i <= 6; i++) {
            int bsize = (int)Math.pow(5, i);
            int csize = (int)Math.pow(10, i);
            rowData.createRow(rowDef, new Object[]{i, bigString(bsize), bigString(csize)});
            expected[i] = rowData.toString(rowDefCache);
            store.writeRow(session, rowData);
        }
        
        final RowCollector rc = store.newRowCollector(session, rowDef.getRowDefId(), 0, 0, null, null, new byte[]{7});
        final ByteBuffer bb = ByteBuffer.allocate(5000000);
        for (int i = 1; i <= 6; i++) {
            assertTrue(rc.hasMore());
            bb.clear();
            assertTrue(rc.collectNextRow(bb));
            bb.flip();
            rowData.reset(bb.array(), 0, bb.limit());
            rowData.prepareRow(0);
            final String actual = rowData.toString(rowDefCache);
            assertEquals(expected[i], actual);
        }
     }

    private String bigString(final int length) {
        final StringBuilder sb= new StringBuilder(length);
        sb.append(length);
        for (int i = sb.length() ; i < length; i++) {
            sb.append("#");
        }
        return sb.toString();
    }
}
