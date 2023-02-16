package cache;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Generic caching class.
 * The cache has a maximum capacity. The most used elements are kept in it.
 * We ensure that the cache will never go over its initial capacity. The number of items
 * inserted can go over this capacity. Only most wanted items remain in the cache
 * "Most wanted" means items just inserted or most retrieved after insertion
 * It is responsibility of user of this class to periodically purge it by either
 * using <code>flush</code> or <code>purge</code>
 *
 *
 * @param <CacheKey> the type of the cached items key
 * @param <CachedValue> the type of the cached items
 */

public class SizedCache<CacheKey, CachedValue> {
    /* number of time get in the cache was successful (key found in the cache) */
    private int cacheHitCount;
    /* number of time get in the cache was not successful (key not found in the cache) */
    private int cacheMissCount;
    /* cache initial capacity */
    private final int cacheMaxSize;
    /* map of cached items */
    private final Map<CacheKey, CachedNode<CacheKey, CachedValue>> cacheMap;
    /* "most wanted" ordered list of cache items */
    private final LinkedList<CachedNode<CacheKey, CachedValue>> cache;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Creates a new cache
     * @param size initial capacity of the cache as non-zero positive int
     */
    public SizedCache(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater or equal to 0");
        }
        this.cacheHitCount = 0;
        this.cacheMissCount = 0;
        this.cacheMaxSize = size;
        this.cacheMap = new HashMap<CacheKey, CachedNode<CacheKey, CachedValue>>(cacheMaxSize);
        this.cache = new LinkedList<CachedNode<CacheKey, CachedValue>>();
    }

    /**
     * flush the cache by discarding all cached items
     */
    public void flush() {
        this.lock.writeLock().lock();
        try {
            cacheMap.clear();
            cache.clear();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * Purges the cache by last access time.
     * @param since all cached items that were not accessed since a given point in time
     *              are discarded
     */
    public void purge(Instant since) {
        Objects.requireNonNull(since,"point in time cannot be null");
        this.lock.writeLock().lock();
        try {
            List<CacheKey> keys = cache.stream().filter(
                    node->node.getTimestamp() <= since.toEpochMilli()).map(
                            node-> node.getReferenceKey()).collect(Collectors.toList());
            keys.forEach(key->
            {
                CachedNode<CacheKey, CachedValue> mode = cacheMap.remove(key);
                cache.remove(mode);
            });
        } finally {
            this.lock.writeLock().unlock();
        }

    }

    /**
     * Puts a new element in the cache.
     * If the key is already present, the cached item is replaced
     *
     * @param key the key used to reference the cached items (cannot be null)
     * @param value the item to be cached (cannot be null)
     */
    public void put(CacheKey key, CachedValue value) {

        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("cached value cannot be null");
        }

        this.lock.writeLock().lock();
        try {
            // do we have already that key ?
            CachedNode node = cacheMap.get(key);
            if (node != null) {
                // cleanup existing ref in the list first
                cache.remove(node);

            } else {
                // key unknown, first check that we have enough capacity
                if (this.cache.size() >= this.cacheMaxSize) {
                    // no room left, make some first by ejecting the last (least wanted) items
                    node = cache.removeLast();
                    cacheMap.remove(node.getReferenceKey());
                }
            }
            // make a new cached item and add it to the maps.
            node = new CachedNode(key, value);
            cacheMap.put(key, node);
            cache.addFirst(node);

        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * Gets a view of cached items
     * The key in the map are ordered the same way items are sorted in the cache
     * i.e most wanted first
     *
     * @return view of cached items. the return elements must not be modified
     */
    public Map<CacheKey, CachedValue> cachedEntries() {
        this.lock.readLock().lock();
        try {
            LinkedHashMap result = new LinkedHashMap<CacheKey, CachedValue>(this.cache.size());
            this.cache.forEach((node) -> {
                result.put(node.referenceKey, node.getValue());
            });
            return result;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    /**
     * Gets a cache items
     * @param key the key of the cached item. cannot be null
     * @return the cached value (can be empty)
     */
    public Optional<CachedValue> get(CacheKey key) {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }
        this.lock.writeLock().lock();
        try {
            CachedNode node = cacheMap.get(key);
            if (node != null) {
                // cache hit. move this item on top of most wanted if not already the case.
                if (!cache.peekFirst().equals(node)) {
                    //not already to the head
                    // have to move to the first place as it is the most accessed now
                    cache.remove(node);
                    cache.addFirst(node);
                }
                this.cacheHitCount++;
                node.setTimestamp(Instant.now().toEpochMilli());
                return (Optional<CachedValue>) Optional.of(node.getValue());
            } else {
                // key not found , that's a cache miss
                this.cacheMissCount++;
                return Optional.empty();
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * Wrapper class used to cache item.
     * This class is used to add meta-data to cached items
     * and to  provide a reliable <code>hashCode</code> and <code>equals</code> implementation
     * While performing lookup, insertion and retireval to the linkedList (cache items list)
     * we must be sure to identify the right element. User is allowed insert same value with different key
     * i.e we cannot trust <code>hashCode</code> and <code>equals</code> provided by cached items themself.
     * @param <CacheKey> the type of the key. must match type used in the cache itself
     * @param <CachedValue> the type of the cached items . must match type used in the cache itself
     */
    private class CachedNode<CacheKey, CachedValue> {

        /* cached value */
        private final CachedValue value;
        /**
         * the key used in the cache for this cached value: link back to cache hash
         * This key is used for computation of the hash. As this coming from key fo the cache hash map
         * unicity of it is ensured bu nature of the hashMap
        */
        private final CacheKey referenceKey;

        /* last access time in milli since EPOC*/
        private long timestamp;

        /**
         * Creates a new cached node
         * @param referenceKey the key used in the cache hashmap
         * @param value the cached value
         */
        public CachedNode(CacheKey referenceKey, CachedValue value) {
            if (referenceKey == null) {
                throw new IllegalArgumentException("reference key cannot be null");
            }
            if (value == null) {
                throw new IllegalArgumentException("cached value cannot be null");
            }
            this.timestamp = Instant.now().toEpochMilli();
            this.referenceKey = referenceKey;
            this.value = value;
        }

        /**
         * Gets last access time of this node
         * @return the access timestamp from epoch in milli
         */
        public long getTimestamp() {
            return timestamp;
        }

        /**
         * updates the access time of this node
         * @param timestamp the access timestamp from epoch in milli
         */
        public void setTimestamp(long timestamp) {
            assert timestamp > 0: "wrong new timestamp";
            this.timestamp = timestamp;
        }

        /**
         * Gets the underlying cached value
         * @return the cache item
         */
        public  CachedValue getValue() {
            return this.value;
        }

        /**
         * Gets the reference key.
         * The key use in the cache hash map. this key allowed to linked this item
         * back to its place in the hashmap
         * @return the key
         */
        public CacheKey getReferenceKey() {
            return this.referenceKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            CachedNode<?, ?> that = (CachedNode<?, ?>) o;
            return referenceKey.equals(that.referenceKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(referenceKey);
        }
    }


}
