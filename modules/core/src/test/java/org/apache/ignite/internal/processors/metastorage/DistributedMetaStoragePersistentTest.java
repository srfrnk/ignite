/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.metastorage;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.processors.cache.persistence.IgniteCacheDatabaseSharedManager;
import org.apache.ignite.internal.processors.cache.persistence.metastorage.MetaStorage;
import org.apache.ignite.internal.processors.metastorage.persistence.DistributedMetaStorageImpl;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.testframework.GridTestUtils;
import org.junit.Ignore;
import org.junit.Test;

import static org.apache.ignite.IgniteSystemProperties.IGNITE_GLOBAL_METASTORAGE_HISTORY_MAX_BYTES;

/**
 * Test for {@link DistributedMetaStorageImpl} with enabled persistence.
 */
public class DistributedMetaStoragePersistentTest extends DistributedMetaStorageTest {
    /** {@inheritDoc} */
    @Override protected boolean isPersistent() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public void before() throws Exception {
        super.before();

        cleanPersistenceDir();
    }

    /** {@inheritDoc} */
    @Override public void after() throws Exception {
        super.after();

        cleanPersistenceDir();
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testRestart() throws Exception {
        IgniteEx ignite = startGrid(0);

        ignite.cluster().active(true);

        ignite.context().distributedMetastorage().write("key", "value");

        stopGrid(0);

        ignite = startGrid(0);

        ignite.cluster().active(true);

        assertEquals("value", ignite.context().distributedMetastorage().read("key"));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testJoinDirtyNode() throws Exception {
        IgniteEx ignite = startGrid(0);

        startGrid(1);

        ignite.cluster().active(true);

        ignite.context().distributedMetastorage().write("key1", "value1");

        stopGrid(1);

        stopGrid(0);

        ignite = startGrid(0);

        ignite.cluster().active(true);

        ignite.context().distributedMetastorage().write("key2", "value2");

        IgniteEx newNode = startGrid(1);

        assertEquals("value1", newNode.context().distributedMetastorage().read("key1"));

        assertEquals("value2", newNode.context().distributedMetastorage().read("key2"));

        assertDistributedMetastoragesAreEqual(ignite, newNode);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testJoinDirtyNodeFullData() throws Exception {
        System.setProperty(IGNITE_GLOBAL_METASTORAGE_HISTORY_MAX_BYTES, "0");

        IgniteEx ignite = startGrid(0);

        startGrid(1);

        ignite.cluster().active(true);

        ignite.context().distributedMetastorage().write("key1", "value1");

        stopGrid(1);

        stopGrid(0);

        ignite = startGrid(0);

        ignite.cluster().active(true);

        ignite.context().distributedMetastorage().write("key2", "value2");

        ignite.context().distributedMetastorage().write("key3", "value3");

        IgniteEx newNode = startGrid(1);

        assertEquals("value1", newNode.context().distributedMetastorage().read("key1"));

        assertEquals("value2", newNode.context().distributedMetastorage().read("key2"));

        assertEquals("value3", newNode.context().distributedMetastorage().read("key3"));

        assertDistributedMetastoragesAreEqual(ignite, newNode);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testJoinNodeWithLongerHistory() throws Exception {
        IgniteEx ignite = startGrid(0);

        startGrid(1);

        ignite.cluster().active(true);

        ignite.context().distributedMetastorage().write("key1", "value1");

        stopGrid(1);

        ignite.context().distributedMetastorage().write("key2", "value2");

        stopGrid(0);

        ignite = startGrid(1);

        startGrid(0);

        awaitPartitionMapExchange();

        assertEquals("value1", ignite.context().distributedMetastorage().read("key1"));

        assertEquals("value2", ignite.context().distributedMetastorage().read("key2"));

        assertDistributedMetastoragesAreEqual(ignite, grid(0));
    }

    /**
     * @throws Exception If failed.
     */
    @Test @Ignore
    public void testJoinNodeWithoutEnoughHistory() throws Exception {
        System.setProperty(IGNITE_GLOBAL_METASTORAGE_HISTORY_MAX_BYTES, "0");

        IgniteEx ignite = startGrid(0);

        startGrid(1);

        ignite.cluster().active(true);

        ignite.context().distributedMetastorage().write("key1", "value1");

        stopGrid(1);

        ignite.context().distributedMetastorage().write("key2", "value2");

        ignite.context().distributedMetastorage().write("key3", "value3");

        stopGrid(0);

        ignite = startGrid(1);

        startGrid(0);

        awaitPartitionMapExchange();

        assertEquals("value1", ignite.context().distributedMetastorage().read("key1"));

        assertEquals("value2", ignite.context().distributedMetastorage().read("key2"));

        assertEquals("value3", ignite.context().distributedMetastorage().read("key3"));

        assertDistributedMetastoragesAreEqual(ignite, grid(0));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testNamesCollision() throws Exception {
        IgniteEx ignite = startGrid(0);

        ignite.cluster().active(true);

        IgniteCacheDatabaseSharedManager dbSharedMgr = ignite.context().cache().context().database();

        MetaStorage locMetastorage = dbSharedMgr.metaStorage();

        DistributedMetaStorage distributedMetastorage = ignite.context().distributedMetastorage();

        dbSharedMgr.checkpointReadLock();

        try {
            locMetastorage.write("key", "localValue");
        }
        finally {
            dbSharedMgr.checkpointReadUnlock();
        }

        distributedMetastorage.write("key", "globalValue");

        dbSharedMgr.checkpointReadLock();

        try {
            assertEquals("localValue", locMetastorage.read("key"));
        }
        finally {
            dbSharedMgr.checkpointReadUnlock();
        }

        assertEquals("globalValue", distributedMetastorage.read("key"));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testUnstableTopology() throws Exception {
        int cnt = 8;

        startGridsMultiThreaded(cnt);

        grid(0).cluster().active(true);

        stopGrid(0);

        startGrid(0);

        AtomicInteger gridIdxCntr = new AtomicInteger(0);

        AtomicBoolean stop = new AtomicBoolean();

        IgniteInternalFuture<?> fut = multithreadedAsync(() -> {
            int gridIdx = gridIdxCntr.incrementAndGet();

            try {
                while (!stop.get()) {
                    stopGrid(gridIdx, true);

                    Thread.sleep(100L);

                    startGrid(gridIdx);

                    Thread.sleep(100L);
                }
            }
            catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }, cnt - 1);

        long start = System.currentTimeMillis();

        long duration = GridTestUtils.SF.applyLB(15_000, 5_000);

        try {
            for (int i = 0; System.currentTimeMillis() < start + duration; i++) {
                metastorage(0).write(
                    "key" + i, Integer.toString(ThreadLocalRandom.current().nextInt(1000))
                );
            }
        }
        finally {
            stop.set(true);

            fut.get();
        }

        awaitPartitionMapExchange();

        for (int i = 0; i < cnt; i++) {
            DistributedMetaStorage distributedMetastorage = metastorage(i);

            assertNull(U.field(distributedMetastorage, "startupExtras"));
        }

        for (int i = 1; i < cnt; i++)
            assertDistributedMetastoragesAreEqual(grid(0), grid(i));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testWrongStartOrder1() throws Exception {
        System.setProperty(IGNITE_GLOBAL_METASTORAGE_HISTORY_MAX_BYTES, "0");

        int cnt = 4;

        startGridsMultiThreaded(cnt);

        grid(0).cluster().active(true);

        metastorage(2).write("key1", "value1");

        stopGrid(2);

        metastorage(1).write("key2", "value2");

        stopGrid(1);

        metastorage(0).write("key3", "value3");

        stopGrid(0);

        metastorage(3).write("key4", "value4");

        stopGrid(3);


        for (int i = 0; i < cnt; i++)
            startGrid(i);

        awaitPartitionMapExchange();

        for (int i = 1; i < cnt; i++)
            assertDistributedMetastoragesAreEqual(grid(0), grid(i));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testWrongStartOrder2() throws Exception {
        System.setProperty(IGNITE_GLOBAL_METASTORAGE_HISTORY_MAX_BYTES, "0");

        int cnt = 6;

        startGridsMultiThreaded(cnt);

        grid(0).cluster().active(true);

        metastorage(4).write("key1", "value1");

        stopGrid(4);

        metastorage(3).write("key2", "value2");

        stopGrid(3);

        metastorage(0).write("key3", "value3");

        stopGrid(0);

        stopGrid(2);

        metastorage(1).write("key4", "value4");

        stopGrid(1);

        metastorage(5).write("key5", "value5");

        stopGrid(5);


        startGrid(1);

        startGrid(0);

        stopGrid(1);

        for (int i = 1; i < cnt; i++)
            startGrid(i);

        awaitPartitionMapExchange();

        for (int i = 1; i < cnt; i++)
            assertDistributedMetastoragesAreEqual(grid(0), grid(i));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testWrongStartOrder3() throws Exception {
        System.setProperty(IGNITE_GLOBAL_METASTORAGE_HISTORY_MAX_BYTES, "0");

        int cnt = 5;

        startGridsMultiThreaded(cnt);

        grid(0).cluster().active(true);

        metastorage(3).write("key1", "value1");

        stopGrid(3);

        stopGrid(0);

        metastorage(2).write("key2", "value2");

        stopGrid(2);

        metastorage(1).write("key3", "value3");

        stopGrid(1);

        metastorage(4).write("key4", "value4");

        stopGrid(4);


        startGrid(1);

        startGrid(0);

        stopGrid(1);

        for (int i = 1; i < cnt; i++)
            startGrid(i);

        awaitPartitionMapExchange();

        for (int i = 1; i < cnt; i++)
            assertDistributedMetastoragesAreEqual(grid(0), grid(i));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testWrongStartOrder4() throws Exception {
        System.setProperty(IGNITE_GLOBAL_METASTORAGE_HISTORY_MAX_BYTES, "0");

        int cnt = 6;

        startGridsMultiThreaded(cnt);

        grid(0).cluster().active(true);

        metastorage(4).write("key1", "value1");

        stopGrid(4);

        stopGrid(0);

        metastorage(3).write("key2", "value2");

        stopGrid(3);

        metastorage(2).write("key3", "value3");

        stopGrid(2);

        metastorage(1).write("key4", "value4");

        stopGrid(1);

        metastorage(5).write("key5", "value5");

        stopGrid(5);


        startGrid(2);

        startGrid(0);

        stopGrid(2);

        for (int i = 1; i < cnt; i++)
            startGrid(i);

        awaitPartitionMapExchange();

        for (int i = 1; i < cnt; i++)
            assertDistributedMetastoragesAreEqual(grid(0), grid(i));
    }

    /**
     * @throws Exception If failed.
     */
    @Test @SuppressWarnings("ThrowableNotThrown")
    public void testInactiveClusterWrite() throws Exception {
        startGrid(0);

        GridTestUtils.assertThrowsAnyCause(log, () -> {
            metastorage(0).write("key", "value");

            return null;
        }, IllegalStateException.class, "Ignite cluster is not active");

        GridTestUtils.assertThrowsAnyCause(log, () -> {
            metastorage(0).remove("key");

            return null;
        }, IllegalStateException.class, "Ignite cluster is not active");
    }

    /**
     * @throws Exception If failed.
     */
    @Test @SuppressWarnings("ThrowableNotThrown")
    public void testConflictingData() throws Exception {
        startGrid(0);

        startGrid(1);

        grid(0).cluster().active(true);

        stopGrid(0);

        metastorage(1).write("key", "value1");

        stopGrid(1);

        startGrid(0);

        grid(0).cluster().active(true);

        metastorage(0).write("key", "value2");

        GridTestUtils.assertThrowsAnyCause(
            log,
            () -> startGrid(1),
            IgniteSpiException.class,
            "Joining node has conflicting distributed metastorage data"
        );
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testFailover1() throws Exception {
        System.setProperty(IGNITE_GLOBAL_METASTORAGE_HISTORY_MAX_BYTES, "0");

        startGrid(0);

        startGrid(1);

        grid(0).cluster().active(true);

        stopGrid(1);

        metastorage(0).write("key1", "val1");

        metastorage(0).write("key9", "val9");

        IgniteCacheDatabaseSharedManager dbSharedMgr = grid(0).context().cache().context().database();

        dbSharedMgr.checkpointReadLock();

        try {
            dbSharedMgr.metaStorage().remove("\u0000key-key9");
        }
        finally {
            dbSharedMgr.checkpointReadUnlock();
        }

        stopGrid(0);

        startGrid(0);

        startGrid(1);

        awaitPartitionMapExchange();

        assertEquals("val9", metastorage(1).read("key9"));

        assertDistributedMetastoragesAreEqual(grid(0), grid(1));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testFailover2() throws Exception {
        System.setProperty(IGNITE_GLOBAL_METASTORAGE_HISTORY_MAX_BYTES, "0");

        startGrid(0);

        startGrid(1);

        grid(0).cluster().active(true);

        stopGrid(1);

        metastorage(0).write("key9", "val9");

        metastorage(0).write("key1", "val1");

        IgniteCacheDatabaseSharedManager dbSharedMgr = grid(0).context().cache().context().database();

        dbSharedMgr.checkpointReadLock();

        try {
            dbSharedMgr.metaStorage().remove("\u0000key-key1");
        }
        finally {
            dbSharedMgr.checkpointReadUnlock();
        }

        stopGrid(0);

        startGrid(0);

        startGrid(1);

        awaitPartitionMapExchange();

        assertEquals("val1", metastorage(1).read("key1"));

        assertDistributedMetastoragesAreEqual(grid(0), grid(1));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testFailover3() throws Exception {
        System.setProperty(IGNITE_GLOBAL_METASTORAGE_HISTORY_MAX_BYTES, "0");

        startGrid(0);

        startGrid(1);

        grid(0).cluster().active(true);

        stopGrid(1);

        metastorage(0).write("key1", "val1");

        metastorage(0).write("key9", "val9");

        metastorage(0).write("key5", "val5");

        IgniteCacheDatabaseSharedManager dbSharedMgr = grid(0).context().cache().context().database();

        dbSharedMgr.checkpointReadLock();

        try {
            dbSharedMgr.metaStorage().write("\u0000key-key5", "wrong-value");
        }
        finally {
            dbSharedMgr.checkpointReadUnlock();
        }

        stopGrid(0);

        startGrid(0);

        startGrid(1);

        awaitPartitionMapExchange();

        assertEquals("val5", metastorage(1).read("key5"));

        assertDistributedMetastoragesAreEqual(grid(0), grid(1));
    }
}