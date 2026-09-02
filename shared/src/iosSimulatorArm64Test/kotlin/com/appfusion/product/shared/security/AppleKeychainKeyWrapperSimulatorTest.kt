package com.appfusion.product.shared.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AppleKeychainKeyWrapperSimulatorTest {
    @Test
    fun keychainKekPersistsAuthenticatesAndDeletes() = runBlocking {
        val service = "com.appfusion.product.secureblob.probe"
        val account = "simulator-kek"
        val wrapper = AppleKeychainKeyWrapper(service = service, account = account)
        wrapper.deleteForProbe()
        try {
            val dataKey = ByteArray(32) { (it * 5 + 11).toByte() }
            val wrapped = wrapper.wrap(dataKey)
            assertTrue(wrapper.keyExistsForProbe())
            assertContentEquals(dataKey, wrapper.unwrap(wrapped))

            val secondWrapper = AppleKeychainKeyWrapper(service = service, account = account)
            assertContentEquals(dataKey, secondWrapper.unwrap(wrapped))

            val tampered = wrapped.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            var rejected = false
            try {
                secondWrapper.unwrap(tampered)
            } catch (_: Throwable) {
                rejected = true
            }
            assertTrue(rejected, "tampered wrapped key must fail authentication")

            val secureBlob = SecureBlobService(secondWrapper)
            val plaintext = "apple keychain backed secure blob".encodeToByteArray()
            val encoded = secureBlob.protect(plaintext)
            assertContentEquals(plaintext, secureBlob.unprotect(encoded))
        } finally {
            wrapper.deleteForProbe()
        }
        assertFalse(wrapper.keyExistsForProbe())
    }
}
