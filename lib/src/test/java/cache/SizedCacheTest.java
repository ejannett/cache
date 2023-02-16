package cache;

import org.junit.Test;
import static org.junit.Assert.*;

import cache.SizedCache;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class SizedCacheTest {
    @Test
    public void testFlush() {
        SizedCache<String, String> cache = new SizedCache<String, String>(10);
        cache.flush();
        assertTrue("cache should be empty",cache.cachedEntries().size() == 0);

        cache.put("X","x");
        cache.flush();
        assertTrue("cache should be empty after one insertion",cache.cachedEntries().size() == 0);

        cache.put("X1","x1");
        cache.put("X2","x2");
        cache.put("X3","x3");
        cache.flush();
        assertTrue("cache should be empty after multiple insertions",cache.cachedEntries().size() == 0);
    }
    @Test
    public void testPurge() {

        SizedCache<String, String> cache = new SizedCache<String, String>(3);
        cache.put("X1","x1");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            //ignored
        }
        cache.put("X2","x2");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            //ignored
        }
        cache.put("X3","x3");

        cache.purge(Instant.now().minus(1, ChronoUnit.SECONDS));

        assertTrue("key should have been pushed away",cache.get("X1").isEmpty());
        assertTrue("key should have been pushed away",cache.get("X2").isEmpty());
        assertFalse("key should have been pushed away",cache.get("X3").isEmpty());


        cache.put("X1","x1");
        cache.put("X2","x2");
        cache.put("X3","x3");

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            //ignored
        }
        cache.purge(Instant.now().minus(1, ChronoUnit.NANOS));
        assertTrue("key should have been pushed away",cache.get("X1").isEmpty());
        assertTrue("key should have been pushed away",cache.get("X2").isEmpty());
        assertTrue("key should have been pushed away",cache.get("X3").isEmpty());

    }
    @Test
    public void testPut() {
        SizedCache<String, String> cache = new SizedCache<String, String>(3);
        cache.put("X1","x1");
        cache.put("X2","x2");
        cache.put("X3","x3");

        assertEquals("cannot get back item","x2",cache.get("X2").get());

        cache.put("X4","x4");

        assertTrue("key should have been pushed away",cache.get("X1").isEmpty());

    }
    @Test
    public void testPutSameKey() {
        SizedCache<String, String> cache = new SizedCache<String, String>(1);
        cache.put("X1","x1");
        cache.put("X1","x11");

        assertEquals("cannot get correct value after update","x11",cache.get("X1").get());


    }


    void validateMapping(Map<String, String> values, int size) {
        SizedCache<String, String> cache = new SizedCache<String, String>(size);
        values.forEach((k, v) -> {
            cache.put(k, v);
        });

        // cache keys list length is minimum between values lenght and cache size
        int limit = size <= values.size() ? size : values.size();

        // validate keys order
        // 1. get the cache keys
        List<String> keys = cache.cachedEntries().keySet().stream().collect(Collectors.toList());
        // 1.  order is reverse compare to insertion order
        List<String> expectedKeys = values.keySet().stream().collect(Collectors.toList());
        // 2. order is reverse compare to insertion order: last to be inserted gets more weight
        Collections.reverse(expectedKeys);

        // 3.  get sub list as size won't exceeed cache size
        assertEquals(String.format("key list do not match expected %s <> actual %s",
                expectedKeys,
                keys
        ), keys, expectedKeys.subList(0, limit));


        // check cache content
        for (Map.Entry<String, String> entry : cache.cachedEntries().entrySet()) {
            assertEquals(String.format("wrong value for key [%s], expecting [%s] got [%s]",
                    entry.getKey(),
                    values.get(entry.getKey()),
                    entry.getValue()
            ), entry.getValue(), values.get(entry.getKey()));

        }

    }
    @Test
    public void testCachedEntries() {
        validateMapping(Map.of("A", "a", "B", "b"), 3);
        validateMapping(Map.of("A", "a", "B", "b"), 1);
        validateMapping(Map.of("A", "a", "B", "b", "C", "c"), 2);
        validateMapping(Map.of("A", "a", "B", "b", "C", "c"), 6);
        validateMapping(Map.of(), 6);
    }
    @Test
    public void testGet() {

        SizedCache<String, String> cache = new SizedCache<String, String>(5);
        cache.put("A", "a");
        //dumpCache(cache);
        String headKey = cache.cachedEntries().keySet().stream().sequential().findFirst().get();
        assertEquals("unexpected head of cache, wanted [A] got [" + headKey + "]", "A", headKey);
        assertEquals("cannot get back item","a",cache.get("A").get());

        cache.put("B", "b");
        cache.put("C", "c");
        //dumpCache(cache);

        headKey = cache.cachedEntries().keySet().stream().sequential().findFirst().get();
        assertEquals("unexpected head of cache, wanted [C] got [" + headKey + "]", "C", headKey);
        assertEquals("cannot get back item","c",cache.get("C").get());

        cache.get("B");
        //dumpCache(cache);
        headKey = cache.cachedEntries().keySet().stream().sequential().findFirst().get();
        assertEquals("unexpected head of cache, wanted [B] got [" + headKey + "]", "B", headKey);

        cache.put("A", "aa");
        //dumpCache(cache);
        headKey = cache.cachedEntries().keySet().stream().sequential().findFirst().get();
        assertEquals("unexpected head of cache, wanted [A] got [" + headKey + "]", "A", headKey);


        cache.get("C");
        cache.get("C");
        cache.get("C");
        //dumpCache(cache);
        headKey = cache.cachedEntries().keySet().stream().sequential().findFirst().get();
        assertEquals("unexpected head of cache, wanted [C] got [" + headKey + "]", "C", headKey);
        assertEquals("cannot get back item","c",cache.get("C").get());



    }
    @Test
    public void testGetNonExistingKey()  {
        SizedCache<String, String> cache = new SizedCache<String, String>(5);
        cache.put("A", "a");
        cache.put("B", "b");
        assertTrue("none existing key returned something",cache.get("NOT EXISTING").isEmpty());
    }
    @Test
    public void testCacheSize() {
        SizedCache<String, String> cache = new SizedCache<String, String>(3);
        // should never go further 3 items
        cache.put("A", "a");
        cache.put("B", "b");
        cache.put("C", "c");
        cache.put("D", "d");

        // cache size
        AtomicInteger itemCount = new AtomicInteger();
        AtomicBoolean gotIt = new AtomicBoolean(false);
        cache.cachedEntries().forEach((k, v) -> {
            if (k.equals("A")) {
                gotIt.set(true);
            }
            itemCount.getAndIncrement();
        });
        assertFalse("cache size went over limit", (itemCount.get() > 3));
        assertFalse("unexpected cache key", gotIt.get());

    }
    @Test
    public void testIllegalCacheSize() {
        boolean gotIt = false;
        try {
            SizedCache<String, String> cache = new SizedCache<String, String>(0);
        } catch (IllegalArgumentException e) {
            gotIt = true;
        }
        assertTrue("was expecting IllegalArgumentException for 0 size",gotIt);
    }
    @Test
    public void testIllegalCachePutKeyArgument()  {
        boolean gotIt = false;
        try {
            SizedCache<String, String> cache = new SizedCache<String, String>(1);
            cache.put(null,"A");
        } catch (IllegalArgumentException e) {
            gotIt = true;
            // good
        }
        assertTrue("was expecting IllegalArgumentException for null key",gotIt);
    }
    @Test
    public void testIllegalCachePutValueArgument()  {
        boolean gotIt = false;
        try {
            SizedCache<String, String> cache = new SizedCache<String, String>(1);
            cache.put("A",null);
        } catch (IllegalArgumentException e) {
            gotIt = true;
            // good
        }
        assertTrue("was expecting IllegalArgumentException for null value",gotIt);
    }

    private void dumpCache(SizedCache<String, String> cache) {
        System.out.printf("================\n");
        cache.cachedEntries().forEach((k, v) -> {
            System.out.printf("%s -> %s\n", k, v);
        });
        System.out.printf("================\n");

    }

}