package com.nshare.airplay.crypto

/**
 * FairPlay SAP handshake used by AirPlay screen mirroring to deliver the stream key.
 * The heavy lifting lives in the native (GPLv3) playfair library, see src/main/cpp.
 */
class FairPlay {
    private var keyMessage: ByteArray? = null

    /** fp-setup with a 16-byte body. */
    fun setup(request: ByteArray): ByteArray? {
        keyMessage = null
        return nativeSetup(request)
    }

    /** fp-setup with a 164-byte body. The request is kept for [decryptKey]. */
    fun handshake(request: ByteArray): ByteArray? {
        val res = nativeHandshake(request) ?: return null
        keyMessage = request.copyOf()
        return res
    }

    /** Decrypts the 72-byte "ekey" from SETUP into the 16-byte AES key. */
    fun decryptKey(ekey: ByteArray): ByteArray? {
        val msg = keyMessage ?: return null
        return nativeDecrypt(msg, ekey)
    }

    companion object {
        init {
            System.loadLibrary("nsfairplay")
        }

        @JvmStatic private external fun nativeSetup(request: ByteArray): ByteArray?
        @JvmStatic private external fun nativeHandshake(request: ByteArray): ByteArray?
        @JvmStatic private external fun nativeDecrypt(keyMessage: ByteArray, ekey: ByteArray): ByteArray?
    }
}
