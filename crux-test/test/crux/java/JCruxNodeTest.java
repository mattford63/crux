package crux.java;

import clojure.lang.*;
import org.junit.*;
import crux.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;

import static crux.java.TestUtils.*;

public class JCruxNodeTest {
    private static final Keyword documentId = Keyword.intern("myDoc");
    private static final Keyword versionId = Keyword.intern("version");
    private static Map<Keyword, Object> document;
    private static Map<Keyword, Object> config;

    private ICruxAPI node;

    @BeforeClass
    public static void beforeClass() {
        HashMap<Keyword, Object> _document = new HashMap<>();
        _document.put(DB_ID, documentId);
        _document.put(versionId, 1);
        document = _document;

        HashMap<Keyword, Object> nodeConfig = new HashMap<>();
        nodeConfig.put(Keyword.intern("slow-queries-min-threshold"), Duration.ofSeconds(-1));
        HashMap<Keyword, Object> _config = new HashMap<>();
        _config.put(Keyword.intern("crux/node"), nodeConfig);
        config = _config;
    }

    @Before
    public void before() {
        node = Crux.startNode(config);
    }

    @After
    public void after() {
        close(node);
        node = null;
    }

    /*
     ICruxIngestAPI Tests
     */
    @Test
    public void submitTxTest() {
        TransactionInstant tx = put();

        Assert.assertEquals(0L, (long) tx.getId());
        Assert.assertNotNull(tx.getTime());
    }

    @Test
    public void openTxLogTest() {
        TransactionInstant tx = put();
        sync();

        ICursor<Map<Keyword, ?>> txLog = node.openTxLog(-1L, false);
        Assert.assertTrue(txLog.hasNext());
        Map<Keyword, ?> txLogEntry = txLog.next();
        Assert.assertFalse(txLog.hasNext());

        Assert.assertEquals(tx.getId(), txLogEntry.get(TX_ID));
        Assert.assertEquals(tx.getTime(), txLogEntry.get(TX_TIME));
        Assert.assertEquals(3, txLogEntry.size());

        @SuppressWarnings("unchecked")
        List<List<?>> events = (List<List<?>>) txLogEntry.get(TX_EVENTS);
        Assert.assertEquals(1, events.size());
        List<?> event = events.get(0);
        Assert.assertEquals(3, event.size());
        Assert.assertEquals(PUT, event.get(0));

        txLog = node.openTxLog(-1L, true);
        Assert.assertTrue(txLog.hasNext());
        txLogEntry = txLog.next();
        Assert.assertFalse(txLog.hasNext());

        Assert.assertEquals(tx.getId(), txLogEntry.get(TX_ID));
        Assert.assertEquals(tx.getTime(), txLogEntry.get(TX_TIME));
        Assert.assertEquals(3, txLogEntry.size());

        assertTxOps((LazySeq) txLogEntry.get(TX_OPS));
    }

    /*
    ICruxAPI tests.
    Note that not testing the ones that return an ICruxDatasource as these will be tested as part of JCruxDatasourceTest
     */
    @Test
    public void statusTest() {
        Map<Keyword, ?> status = node.status();
        Assert.assertNotNull(status);
        assertContains(status, false,"crux.version/version");
        assertContains(status, true, "crux.version/revision");
        assertContains(status, false,"crux.kv/kv-store");
        assertContains(status, false,"crux.kv/estimate-num-keys");
        assertContains(status, true, "crux.kv/size");
        assertContains(status, false, "crux.index/index-version");
        assertContains(status, true, "crux.doc-log/consumer-state");
        assertContains(status, true, "crux.tx-log/consumer-state");
    }

    @Test(expected = NodeOutOfSyncException.class)
    public void hasTxCommittedThrowsTest() {
        TransactionInstant tx = put();
        node.hasTxCommitted(tx);
    }

    @Test
    public void hasTxCommittedTest() {
        TransactionInstant tx = put();
        sync();
        Assert.assertTrue(node.hasTxCommitted(tx));
    }

    @Test(expected = TimeoutException.class)
    public void syncThrowsTest() {
        for (int i=0; i<100; i++) {
            put();
        }
        node.sync(Duration.ZERO);
    }

    @Test
    public void syncTest() {
        TransactionInstant tx = put();
        Date txTime = tx.getTime();
        Date fromSync = sync();
        Assert.assertEquals(txTime, fromSync);
    }

    @Test(expected = TimeoutException.class)
    public void awaitTxTimeThrowsTest() {
        for (int i=0; i<100; i++) {
            put();
        }
        TransactionInstant tx = put();

        Date txTime = tx.getTime();
        node.awaitTxTime(txTime, Duration.ZERO);
    }

    @Test
    public void awaitTxTimeTest() {
        TransactionInstant tx = put();

        Date txTime = tx.getTime();
        Date past = Date.from(txTime.toInstant().minusMillis(100));
        Date fromAwait = node.awaitTxTime(past, duration);
        Assert.assertEquals(txTime, fromAwait);
    }

    @Test(expected = TimeoutException.class)
    public void awaitTxThrowsTest() {
        for (int i=0; i<100; i++) {
            put();
        }
        TransactionInstant tx = put();
        node.awaitTx(tx, Duration.ZERO);
    }

    @Test
    public void awaitTxTest() {
        TransactionInstant tx = put();
        node.awaitTx(tx, duration);
    }

    @Test
    public void listenTest() {
        final Object[] events = new Object[]{null};
        AutoCloseable listener = node.listen(ICruxAPI.TX_INDEXED_EVENT_OPTS, (Map<Keyword,?> e) -> {
            events[0] = e;
        });
        TransactionInstant tx = put();
        sync();
        sleep(100);
        @SuppressWarnings("unchecked")
        Map<Keyword, ?> event = (Map<Keyword, ?>) events[0];
        Assert.assertNotNull(event);
        Assert.assertEquals(5, event.size());
        Assert.assertEquals(Keyword.intern("crux/indexed-tx"), event.get(Keyword.intern("crux/event-type")));
        Assert.assertTrue((Boolean) event.get(Keyword.intern("committed?")));
        Assert.assertEquals(tx.getTime(), event.get(TX_TIME));
        Assert.assertEquals(0L, event.get(TX_ID));
        assertTxOps((LazySeq) event.get(Keyword.intern("crux/tx-ops")));

        try {
            listener.close();
        } catch (Exception e) {
            Assert.fail();
        }

        events[0] = null;

        put();
        sync();
        sleep(100);

        Assert.assertNull(events[0]);
    }

    @Test
    public void latestCompletedTxTest() {
        TransactionInstant tx = put();
        sync();
        TransactionInstant latest = node.latestCompletedTx();
        Assert.assertEquals(tx, latest);
    }

    @Test
    public void latestSubmittedTxTest() {
        Assert.assertNull(node.latestSubmittedTx());
        TransactionInstant tx = put();
        TransactionInstant latest = node.latestSubmittedTx();
        //Latest Submitted doesn't give us the TxTime
        TransactionInstant compare = TransactionInstant.factory(tx.getId());
        Assert.assertEquals(compare, latest);
    }

    @Test
    public void attributeStatsTest() {
        put();
        sync();
        Map<Keyword, ?> stats = node.attributeStats();
        Assert.assertEquals(1, stats.get(DB_ID));
        Assert.assertEquals(1, stats.get(versionId));
        Assert.assertEquals(2, stats.size());
    }

    @Test
    public void activeQueriesTest() {
        List<IQueryState> active = node.activeQueries();
        Assert.assertEquals(0, active.size());
    }

    @Test
    public void recentQueriesTest() {
        put();
        sync();
        query();
        sleep(10);
        List<IQueryState> recent = node.recentQueries();
        Assert.assertEquals(1, recent.size());
    }

    @Test
    public void slowestQueriesTest() {
        put();
        sync();
        query();
        sleep(10);
        List<IQueryState> slowest = node.slowestQueries();
        Assert.assertEquals(1, slowest.size());
    }

    /*
    Utils
     */
    private void assertContains(Map<Keyword, ?> map, boolean canBeNull, String string) {
        Keyword keyword = Keyword.intern(string);
        if (canBeNull) {
            Assert.assertTrue(map.containsKey(keyword));
        }
        else {
            Assert.assertNotNull(map.get(keyword));
        }
    }

    private TransactionInstant put() {
        ArrayList<List<?>> tx = new ArrayList<>();
        ArrayList<Object> txOp = new ArrayList<>();
        txOp.add(PUT);
        txOp.add(document);
        tx.add(txOp);
        return node.submitTx((List<List<?>>) tx);
    }

    private Collection<List<?>> query() {
        HashMap<Keyword, Object> map = new HashMap<>();
        map.put(Keyword.intern("find"), PersistentVector.create(listOf(Symbol.intern("d"))));
        map.put(Keyword.intern("where"), PersistentVector.create(listOf(PersistentVector.create(listOf(Symbol.intern("d"), DB_ID)))));
        return node.db().query(PersistentArrayMap.create(map));
    }

    private void assertTxOps(LazySeq seq) {
        Object[] txOps = seq.toArray();
        Assert.assertEquals(1, txOps.length);
        IPersistentVector txOp = (IPersistentVector) txOps[0];
        Assert.assertEquals(2, txOp.length());
        Assert.assertEquals(PUT, txOp.nth(0));
        Assert.assertEquals(document, txOp.nth(1));
    }

    private Date sync() {
        return node.sync(duration);
    }
}
