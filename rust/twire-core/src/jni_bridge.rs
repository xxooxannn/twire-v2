//! JNI surface for `com.perflyst.twire.chat.NativeChat`.
//!
//! Handles are opaque `long`s into a registry of immutable [`EmoteIndex`]es.
//! Every entry point is wrapped in `catch_unwind`: a panic must never unwind
//! into the ART runtime, where it would abort the process. Failures degrade to
//! empty results, and Kotlin falls back to its own implementation when the
//! library is missing or a handle is stale.

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jint, jintArray, jlong, jsize};
use jni::JNIEnv;

use crate::{EmoteIndex, parse_emote_tag};

static REGISTRY: OnceLock<Mutex<HashMap<u64, Arc<EmoteIndex>>>> = OnceLock::new();
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

fn registry() -> &'static Mutex<HashMap<u64, Arc<EmoteIndex>>> {
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn lookup(handle: jlong) -> Option<Arc<EmoteIndex>> {
    if handle <= 0 {
        return None;
    }
    registry()
        .lock()
        .ok()
        .and_then(|map| map.get(&(handle as u64)).cloned())
}

fn to_java_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    // Safe here: every caller passes a `java.lang.String` parameter.
    let java_str = unsafe { env.get_string_unchecked(value) }.ok()?;
    Some(java_str.into())
}

fn new_int_array(env: &mut JNIEnv, values: &[jint]) -> jintArray {
    let array = match env.new_int_array(values.len() as jsize) {
        Ok(array) => array,
        Err(_) => return std::ptr::null_mut(),
    };
    if !values.is_empty() && env.set_int_array_region(&array, 0, values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

/// Builds an index from the Kotlin emote-list order. Returns 0 on failure.
#[no_mangle]
pub extern "system" fn Java_com_perflyst_twire_chat_NativeChat_nativeNewEmoteIndex<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    keywords: JObjectArray<'local>,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| -> Option<jlong> {
        let count = env.get_array_length(&keywords).ok()?;
        let mut owned: Vec<String> = Vec::with_capacity(count as usize);
        for i in 0..count {
            let element = env.get_object_array_element(&keywords, i).ok()?;
            let text = to_java_string(&mut env, &JString::from(element))?;
            owned.push(text);
        }
        let index = EmoteIndex::new(owned);
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        registry().lock().ok()?.insert(handle, Arc::new(index));
        Some(handle as jlong)
    }));
    match result {
        Ok(Some(handle)) => handle,
        _ => 0,
    }
}

/// Drops an index. Safe to call twice or with a stale handle.
#[no_mangle]
pub extern "system" fn Java_com_perflyst_twire_chat_NativeChat_nativeFreeEmoteIndex(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle <= 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut map) = registry().lock() {
            map.remove(&(handle as u64));
        }
    }));
}

/// Scans `message` for emotes. Returns packed triples
/// `[start, len, keywordIndex]` in UTF-16 offsets (see [`crate::EmoteHit`]).
#[no_mangle]
pub extern "system" fn Java_com_perflyst_twire_chat_NativeChat_nativeScanEmotes<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    message: JString<'local>,
) -> jintArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Option<Vec<jint>> {
        let index = lookup(handle)?;
        let text = to_java_string(&mut env, &message)?;
        let hits = index.scan(&text);
        let mut packed = Vec::with_capacity(hits.len() * 3);
        for hit in hits {
            packed.push(hit.start as jint);
            packed.push(hit.len as jint);
            packed.push(hit.keyword as jint);
        }
        Some(packed)
    }));
    match result {
        Ok(Some(packed)) => new_int_array(&mut env, &packed),
        _ => new_int_array(&mut env, &[]),
    }
}

/// Autocomplete search. Returns keyword indices, best match first.
#[no_mangle]
pub extern "system" fn Java_com_perflyst_twire_chat_NativeChat_nativeSearchEmotes<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    query: JString<'local>,
    limit: jint,
) -> jintArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Option<Vec<jint>> {
        let index = lookup(handle)?;
        if limit <= 0 {
            return Some(Vec::new());
        }
        let text = to_java_string(&mut env, &query)?;
        Some(
            index
                .search(&text, limit as usize)
                .into_iter()
                .map(|i| i as jint)
                .collect(),
        )
    }));
    match result {
        Ok(Some(packed)) => new_int_array(&mut env, &packed),
        _ => new_int_array(&mut env, &[]),
    }
}

/// Parses an `emotes` tag value. Returns packed groups:
/// `[idStart, idLen, rangeCount, start, end, ...]` repeated per emote.
#[no_mangle]
pub extern "system" fn Java_com_perflyst_twire_chat_NativeChat_nativeParseEmoteTag<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tag: JString<'local>,
) -> jintArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Option<Vec<jint>> {
        let text = to_java_string(&mut env, &tag)?;
        let groups = parse_emote_tag(&text);
        let mut packed = Vec::with_capacity(groups.len() * 5);
        for group in groups {
            packed.push(group.id_start as jint);
            packed.push(group.id_len as jint);
            packed.push(group.ranges.len() as jint);
            for (start, end) in group.ranges {
                packed.push(start as jint);
                packed.push(end as jint);
            }
        }
        Some(packed)
    }));
    match result {
        Ok(Some(packed)) => new_int_array(&mut env, &packed),
        _ => new_int_array(&mut env, &[]),
    }
}
