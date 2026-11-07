//! LRU eviction of the chunk cache.

mod common;

use std::time::Duration;

use common::temp_dir;
use lfly_audio::DiskCache;

const CHUNK: u64 = 4096;

fn put(cache: &DiskCache, key: &str) {
    cache.set_total_len(key, CHUNK).unwrap();
    cache.write_chunk(key, 0, &vec![7u8; CHUNK as usize]).unwrap();
    // Distinct modification times for the LRU order.
    std::thread::sleep(Duration::from_millis(30));
}

#[test]
fn least_recently_used_entries_go_first() {
    // Room for three entries (chunk + small meta file each), not four.
    let cache = DiskCache::with_chunk_size(temp_dir("evict"), 3 * CHUNK + 100, CHUNK).unwrap();
    for key in ["a", "b", "c"] {
        put(&cache, key);
    }
    assert!(["a", "b", "c"].iter().all(|k| cache.is_complete(k)));

    put(&cache, "d");
    assert!(!cache.has_chunk("a", 0), "the oldest entry is evicted");
    assert!(["b", "c", "d"].iter().all(|k| cache.is_complete(k)));
    assert!(cache.size() <= cache.max_bytes());

    // Using b makes c the oldest.
    cache.touch("b");
    std::thread::sleep(Duration::from_millis(30));
    put(&cache, "e");
    assert!(!cache.has_chunk("c", 0));
    assert!(["b", "d", "e"].iter().all(|k| cache.is_complete(k)));

    // A pinned entry (playing right now) is never evicted, even when it is the oldest.
    cache.pin("b");
    std::thread::sleep(Duration::from_millis(30));
    put(&cache, "f");
    assert!(cache.is_complete("b"), "pinned");
    assert!(!cache.has_chunk("d", 0));
    cache.unpin("b");
}

#[test]
fn damaged_chunks_are_not_served() {
    let dir = temp_dir("damaged");
    let cache = DiskCache::with_chunk_size(&dir, 1 << 20, CHUNK).unwrap();
    cache.set_total_len("x", CHUNK + 10).unwrap();
    cache.write_chunk("x", 0, &vec![1u8; CHUNK as usize]).unwrap();
    cache.write_chunk("x", 1, &[2u8; 9]).unwrap(); // one byte short
    assert!(cache.read_chunk("x", 0).is_some());
    assert!(cache.read_chunk("x", 1).is_none());
    assert!(!cache.is_complete("x"));

    // Opened with another chunk size: the entry is discarded.
    let other = DiskCache::with_chunk_size(&dir, 1 << 20, 2 * CHUNK).unwrap();
    assert_eq!(other.total_len("x"), None);
}
