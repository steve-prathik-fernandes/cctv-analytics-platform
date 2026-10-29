package com.cctv.platform.storage;

import java.util.regex.Pattern;

/** Rules for storage keys. Keys become file paths and signed links, so only plain relative paths pass. */
public final class StorageKeys {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_\\-][A-Za-z0-9_./\\-]*");

    private StorageKeys() {
    }

    /** True for a plain relative path with no "..", no leading slash and at most 512 characters. */
    public static boolean isSafe(String key) {
        return key != null && key.length() <= 512 && SAFE.matcher(key).matches() && !key.contains("..");
    }
}
