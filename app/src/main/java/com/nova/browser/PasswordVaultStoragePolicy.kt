package com.nova.browser

/** Size limits for the encrypted password vault, including read compatibility for older files. */
internal object PasswordVaultStoragePolicy {
    const val MIN_BLOB_BYTES = 28
    const val MAX_VAULT_BYTES = 4 * 1024 * 1024
    const val MAX_LEGACY_VAULT_BYTES = 16 * 1024 * 1024
    const val MAX_LEGACY_B64_CHARS = ((MAX_LEGACY_VAULT_BYTES + 2) / 3) * 4

    fun canRead(size: Int): Boolean = size in MIN_BLOB_BYTES..MAX_LEGACY_VAULT_BYTES

    fun canWrite(packedSize: Int, existingSize: Long, allowReduction: Boolean): Boolean =
        packedSize <= MAX_VAULT_BYTES || (allowReduction && existingSize > MAX_VAULT_BYTES &&
            packedSize < existingSize && packedSize <= MAX_LEGACY_VAULT_BYTES)
}
