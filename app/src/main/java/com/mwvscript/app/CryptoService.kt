package com.mwvscript.app

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * CryptoService
 *
 * ProtectStar™ Extended AES Algorithm (Block:512bit, Rounds:24)
 * + ASDA (Advanced Secure Delete Algorithm) 4-pass file shredder
 *
 * rjsブリッジ: crypto.encrypt / crypto.decrypt / shred.file / shred.fileAsync
 */
object CryptoService {

    // =========================================================
    // Extended AES 実装
    // =========================================================

    private val SBOX = byteArrayOf(
        99, 124, 119, 123, -14, 107, 111, -59, 48, 1, 103, 43, -2, -41, -85, 118,
        -54, -126, -55, 125, -6, 89, 71, -16, -83, -44, -94, -81, -100, -92, 114, -64,
        -73, -3, -109, 38, 54, 63, -9, -52, 52, -91, -27, -15, 113, -40, 49, 21,
        4, -57, 35, -61, 24, -106, 5, -102, 7, 18, -128, -30, -21, 39, -78, 117,
        9, -127, 44, 26, 27, 110, 90, -96, 82, 59, -42, -77, 41, -29, 47, -124,
        83, -47, 0, -19, 32, -4, -79, 91, 106, -53, -66, 57, 74, 76, 88, -49,
        -48, -17, -86, -5, 67, 77, 51, -123, 69, -7, 2, 127, 80, 60, -97, -88,
        81, -93, 64, -113, -110, -99, 56, -11, -68, -74, -38, 33, 16, -1, -13, -46,
        -51, 12, 19, -20, 95, -105, 68, 23, -60, -89, 126, 61, 100, 93, 25, 115,
        96, -127, 79, -36, 34, 42, -112, -120, 70, -18, -72, 20, -34, 94, 11, -37,
        -32, 50, 58, 10, 73, 6, 36, 92, -62, -45, -84, 98, -111, -107, -28, 121,
        -25, -56, 55, 109, -115, -43, 78, -87, 108, 86, -12, -22, 101, 122, -82, 8,
        -70, 120, 37, 46, 28, -90, -76, -58, -24, -35, 116, 31, 75, -67, -117, -118,
        112, 62, -75, 102, 72, 3, -10, 14, 97, 53, 87, -71, -122, -63, 29, -98,
        -31, -8, -104, 17, 105, -39, -114, -108, -101, 30, -121, -23, -50, 85, 40, -33,
        -116, -95, -119, 13, -65, -26, 66, 104, 65, -103, 45, 15, -80, 84, -69, 22
    ).map { it.toByte() }.toByteArray()

    private val SBOX_INV = byteArrayOf(
        82, 9, 106, -43, 48, 54, -91, 56, -65, 64, -93, -98, -127, -13, -41, -5,
        124, -29, 57, -126, -101, 47, -1, -121, 52, -114, 67, 68, -60, -34, -23, -53,
        84, 123, -108, 50, -90, -62, 35, 61, -18, 76, -107, 11, 66, -6, -61, 78,
        8, 46, -95, 102, 40, -39, 36, -78, 118, 91, -94, 73, 109, -117, -47, 37,
        114, -8, -10, 100, -122, 104, -108, 22, -44, -92, 92, -52, 93, 101, -74, -110,
        108, 112, 72, 80, -3, -19, -71, -38, 94, 21, 70, 87, -89, -113, -99, -124,
        -112, -40, -85, 0, -116, -68, -45, 10, -9, -28, 88, 5, -72, -77, 69, 6,
        -48, 44, 30, -113, -54, 63, 15, 2, -63, -81, -67, 3, 1, 19, -118, 107,
        58, -111, 17, 65, 79, 103, -36, -22, -105, -14, -49, -50, -16, -76, -26, 115,
        -106, -84, 116, 34, -25, -83, 53, -123, -30, -7, 55, -24, 28, 117, -33, 110,
        71, -15, 26, 113, 29, 41, -59, -119, 111, -73, 98, 14, -86, 24, -66, 27,
        -4, 86, 62, 75, -58, -46, 121, 32, -102, -37, -64, -2, 120, -51, 90, -12,
        31, -35, -88, 51, -120, 7, -57, 49, -79, 18, 16, 89, 39, -128, -20, 95,
        96, 81, 127, -87, 25, -77, 74, 13, 45, -27, 122, -103, -109, -55, -108, -17,
        -96, -32, 59, 77, -82, 42, -11, -80, -56, -3, -69, 60, -125, 83, -103, 97,
        23, 43, 4, 126, -70, 119, -42, 38, -31, 105, 20, 99, 85, 33, 12, 125
    ).map { it.toByte() }.toByteArray()

    private const val BLOCKSIZE   = 64   // 512 bits
    private const val COLUMNSIZE  = 16
    private const val TOTALROUNDS = 24
    private const val BLOCKSIZE_WORD = BLOCKSIZE / 4

    // ---- RCON ----
    private val RCON: IntArray = IntArray(100).also { rcon ->
        var r = 1
        rcon[0] = r shl 24
        for (i in 1 until rcon.size) {
            r = r shl 1
            if (r >= 0x100) r = r xor 0x11B
            rcon[i] = r shl 24
        }
    }

    // ---- ラウンドキー生成 ----
    private fun generateRoundKeys(password: String, keySize: Int): ByteArray {
        val roundKeys = ByteArray((TOTALROUNDS + 1) * BLOCKSIZE)
        val nk        = keySize / 4
        val originalKey = pbkdf2(password.toByteArray(), "aes-extended".toByteArray(), 100, keySize)

        System.arraycopy(originalKey, 0, roundKeys, 0, originalKey.size)

        val w = IntArray(BLOCKSIZE_WORD * (TOTALROUNDS + 1))
        for (i in 0 until nk) {
            w[i] = byteArrayToInt(originalKey, i * 4)
        }
        for (i in nk until w.size) {
            var temp = w[i - 1]
            if (i % nk == 0 || (i % nk == 4 && nk > 6)) {
                val shifted = shiftWord(temp)
                val ba = intToByteArray(shifted)
                sboxSubstitute(ba)
                temp = byteArrayToInt(ba, 0) xor RCON[i / nk]
            } else if ((i % nk == 8 || i % nk == 12) && nk > 6) {
                val ba = intToByteArray(temp)
                sboxSubstitute(ba)
                temp = byteArrayToInt(ba, 0)
            }
            w[i] = w[i - nk] xor temp
        }

        var idx = originalKey.size
        for (i in nk until w.size) {
            val b = intToByteArray(w[i])
            roundKeys[idx++] = b[0]; roundKeys[idx++] = b[1]
            roundKeys[idx++] = b[2]; roundKeys[idx++] = b[3]
        }
        return roundKeys
    }

    // ---- ブロック暗号化 ----
    private fun encBlock(block: ByteArray, roundKeys: ByteArray) {
        addRoundKey(block, roundKeys, 0)
        for (i in 0 until TOTALROUNDS - 1) {
            sboxSubstitute(block)
            shiftRows(block, true)
            mixColumns(block)
            addRoundKey(block, roundKeys, (i + 1) * BLOCKSIZE)
        }
        sboxSubstitute(block)
        shiftRows(block, true)
        addRoundKey(block, roundKeys, roundKeys.size - BLOCKSIZE)
    }

    // ---- ブロック復号 ----
    private fun decBlock(block: ByteArray, roundKeys: ByteArray) {
        addRoundKey(block, roundKeys, roundKeys.size - BLOCKSIZE)
        for (i in 0 until TOTALROUNDS - 1) {
            shiftRows(block, false)
            invSboxSubstitute(block)
            addRoundKey(block, roundKeys, roundKeys.size - (i + 2) * BLOCKSIZE)
            invMixColumns(block)
        }
        shiftRows(block, false)
        invSboxSubstitute(block)
        addRoundKey(block, roundKeys, 0)
    }

    // ---- 公開API ----

    /**
     * password と plaintext を受け取り、CTRモードで暗号化してBase64を返す
     */
    fun encrypt(password: String, plaintext: String, keySize: Int = 32): String {
        val roundKeys  = generateRoundKeys(password, keySize)
        val input      = plaintext.toByteArray(Charsets.UTF_8)
        val padded     = addPadding(input)
        val iv         = generateIV()
        val result     = ByteArray(padded.size + BLOCKSIZE)
        System.arraycopy(iv, 0, result, 0, BLOCKSIZE)
        var counter    = 0
        val blockCount = padded.size / BLOCKSIZE
        for (i in 0 until blockCount) {
            val ci = iv.copyOf()
            addCounter(ci, counter++)
            encBlock(ci, roundKeys)
            for (j in 0 until BLOCKSIZE) result[BLOCKSIZE + i * BLOCKSIZE + j] = ((ci[j].toInt() xor padded[i * BLOCKSIZE + j].toInt()) and 0xFF).toByte()
        }
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    /**
     * Base64暗号文を復号してUTF-8文字列を返す
     */
    fun decrypt(password: String, base64: String, keySize: Int = 32): String {
        val roundKeys  = generateRoundKeys(password, keySize)
        val ciphertext = Base64.decode(base64, Base64.NO_WRAP)
        val blockCount = ciphertext.size / BLOCKSIZE
        val decResult  = ByteArray((blockCount - 1) * BLOCKSIZE)
        var counter    = 0
        val iv         = ciphertext.copyOfRange(0, BLOCKSIZE)
        for (i in 1 until blockCount) {
            val ci = iv.copyOf()
            addCounter(ci, counter++)
            encBlock(ci, roundKeys)
            for (j in 0 until BLOCKSIZE) decResult[(i - 1) * BLOCKSIZE + j] = ((ci[j].toInt() xor ciphertext[i * BLOCKSIZE + j].toInt()) and 0xFF).toByte()
        }
        return removePadding(decResult).toString(Charsets.UTF_8)
    }

    // ---- 内部実装 ----

    private fun sboxSubstitute(b: ByteArray) {
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            b[i] = SBOX[((v shr 4) * COLUMNSIZE) + (v and 0x0F)]
        }
    }

    private fun invSboxSubstitute(b: ByteArray) {
        for (i in 0 until BLOCKSIZE) {
            val v = b[i].toInt() and 0xFF
            b[i] = SBOX_INV[((v shr 4) * COLUMNSIZE) + (v and 0x0F)]
        }
    }

    private fun shiftRows(block: ByteArray, enc: Boolean) {
        val tmp = block.copyOf()
        val offsets = intArrayOf(0, 1, 4, 5)
        for (i in 1 until 4) {
            val sm = offsets[i]
            for (j in 0 until COLUMNSIZE) {
                val cur = i * COLUMNSIZE + j
                if (enc) {
                    if (cur - sm < i * COLUMNSIZE) block[cur - sm + COLUMNSIZE] = tmp[cur]
                    else block[cur - sm] = tmp[cur]
                } else {
                    if (cur + sm >= (i + 1) * COLUMNSIZE) block[cur + sm - COLUMNSIZE] = tmp[cur]
                    else block[cur + sm] = tmp[cur]
                }
            }
        }
    }

    private fun mixColumns(b: ByteArray) {
        val sp = IntArray(4)
        for (i in 0 until COLUMNSIZE) {
            sp[0] = mul(2, b[i])                xor mul(3, b[COLUMNSIZE+i])      xor (b[2*COLUMNSIZE+i].toInt() and 0xFF) xor (b[3*COLUMNSIZE+i].toInt() and 0xFF)
            sp[1] = (b[i].toInt() and 0xFF)     xor mul(2, b[COLUMNSIZE+i])      xor mul(3, b[2*COLUMNSIZE+i])            xor (b[3*COLUMNSIZE+i].toInt() and 0xFF)
            sp[2] = (b[i].toInt() and 0xFF)     xor (b[COLUMNSIZE+i].toInt() and 0xFF) xor mul(2, b[2*COLUMNSIZE+i])     xor mul(3, b[3*COLUMNSIZE+i])
            sp[3] = mul(3, b[i])                xor (b[COLUMNSIZE+i].toInt() and 0xFF) xor (b[2*COLUMNSIZE+i].toInt() and 0xFF) xor mul(2, b[3*COLUMNSIZE+i])
            for (j in 0 until 4) b[j * COLUMNSIZE + i] = sp[j].toByte()
        }
    }

    private fun invMixColumns(b: ByteArray) {
        val sp = IntArray(4)
        for (i in 0 until COLUMNSIZE) {
            sp[0] = mul(0x0e, b[i]) xor mul(0x0b, b[COLUMNSIZE+i]) xor mul(0x0d, b[2*COLUMNSIZE+i]) xor mul(0x09, b[3*COLUMNSIZE+i])
            sp[1] = mul(0x09, b[i]) xor mul(0x0e, b[COLUMNSIZE+i]) xor mul(0x0b, b[2*COLUMNSIZE+i]) xor mul(0x0d, b[3*COLUMNSIZE+i])
            sp[2] = mul(0x0d, b[i]) xor mul(0x09, b[COLUMNSIZE+i]) xor mul(0x0e, b[2*COLUMNSIZE+i]) xor mul(0x0b, b[3*COLUMNSIZE+i])
            sp[3] = mul(0x0b, b[i]) xor mul(0x0d, b[COLUMNSIZE+i]) xor mul(0x09, b[2*COLUMNSIZE+i]) xor mul(0x0e, b[3*COLUMNSIZE+i])
            for (j in 0 until 4) b[j * COLUMNSIZE + i] = sp[j].toByte()
        }
    }

    private fun mul(x: Int, y: Byte): Int {
        var aa = x.toByte(); var bb = y; var result = 0.toByte()
        var a = aa
        while (a.toInt() != 0) {
            if ((a.toInt() and 1) != 0) result = ((result.toInt() xor bb.toInt()) and 0xFF).toByte()
            val temp = (bb.toInt() and 0x80).toByte()
            bb = (bb.toInt() shl 1).toByte()
            if (temp.toInt() != 0) bb = (bb.toInt() xor 0x1b).toByte()
            a = ((a.toInt() and 0xFF) ushr 1).toByte()
        }
        return result.toInt() and 0xFF
    }

    private fun addRoundKey(block: ByteArray, rk: ByteArray, start: Int) {
        for (i in 0 until BLOCKSIZE) block[i] = ((block[i].toInt() xor rk[i + start].toInt()) and 0xFF).toByte()
    }

    private fun shiftWord(w: Int): Int {
        val first = (w ushr 24).toByte()
        val last  = w shl 8
        val ba    = intToByteArray(last)
        ba[3]     = first
        return byteArrayToInt(ba, 0)
    }

    private fun intToByteArray(n: Int): ByteArray {
        val b = ByteArray(4)
        for (i in 0 until 4) b[3 - i] = ((n ushr (i * 8)) and 0xFF).toByte()
        return b
    }

    private fun byteArrayToInt(b: ByteArray, off: Int): Int {
        var n = 0
        for (i in 0 until 4) n = n or ((b[off + 3 - i].toInt() and 0xFF) shl (i * 8))
        return n
    }

    private fun addCounter(ci: ByteArray, counter: Int) {
        var c = counter
        for (i in 0 until 4) {
            ci[BLOCKSIZE - 1 - i] = (ci[BLOCKSIZE - 1 - i].toInt() xor (c and 0xFF)).toByte()
            c = c ushr 8
        }
    }

    private fun generateIV(): ByteArray {
        val iv = ByteArray(BLOCKSIZE)
        SecureRandom.getInstance("SHA1PRNG").nextBytes(iv)
        return iv
    }

    private fun addPadding(input: ByteArray): ByteArray {
        val size   = input.size / BLOCKSIZE + 1
        val extra  = if ((input.size % BLOCKSIZE) == BLOCKSIZE - 1 || (input.size % BLOCKSIZE) == 0) 1 else 0
        val padded = ByteArray((size + extra) * BLOCKSIZE)
        System.arraycopy(input, 0, padded, 0, input.size)
        padded[input.size] = 0x80.toByte()
        return padded
    }

    private fun removePadding(decResult: ByteArray): ByteArray {
        var idx = decResult.size - 1
        while (decResult[idx] != 0x80.toByte()) idx--
        return decResult.copyOfRange(0, idx)
    }

    // ---- PBKDF2 (SHA-1) ----
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val hLen    = 20
        val derived = ByteArray(dkLen)
        val l       = dkLen / hLen + 1
        val r       = dkLen - (l - 1) * hLen
        for (i in 0 until l) {
            val ti = pbkdf2F(password, salt, iterations, i)
            if (i != l - 1) System.arraycopy(ti, 0, derived, i * hLen, ti.size)
            else System.arraycopy(ti, 0, derived, (l - 1) * hLen, r)
        }
        return derived
    }

    private fun pbkdf2F(p: ByteArray, s: ByteArray, c: Int, i: Int): ByteArray {
        val buf = byteArrayOf(
            ((i and 0xFF000000.toInt()) ushr 24).toByte(),
            ((i and 0x00FF0000) ushr 16).toByte(),
            ((i and 0x0000FF00) ushr 8).toByte(),
            (i and 0xFF).toByte()
        )
        var ui  = prf(p, buf)
        val res = ui.copyOf()
        repeat(c) {
            ui = prf(p, ui)
            for (k in res.indices) res[k] = ((res[k].toInt() xor ui[k].toInt()) and 0xFF).toByte()
        }
        return res
    }

    private fun prf(p: ByteArray, ui: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(p); md.update(ui)
        return md.digest()
    }

    // =========================================================
    // ASDA（4パス安全消去）
    // =========================================================

    /**
     * ファイルを4パスで安全消去する（同期）
     * Pass1: 0xFF上書き
     * Pass2: AES-256暗号化データで上書き
     * Pass3: ビットパターン(0x92,0x49,0x24)で上書き + 書き込み後検証
     * Pass4: CSPRNG乱数で上書き
     * @return 成功でtrue
     */
    fun shredFile(path: String): Boolean {
        val file = java.io.File(path)
        if (!file.exists() || !file.isFile) return false
        val size = file.length().toInt()
        if (size == 0) return file.delete()

        return try {
            val raf = java.io.RandomAccessFile(file, "rw")

            // Pass1: 0xFF上書き
            val buf1 = ByteArray(size) { 0xFF.toByte() }
            raf.seek(0); raf.write(buf1)

            // Pass2: AES-256で暗号化されたランダムデータで上書き
            val rng  = SecureRandom()
            val key  = ByteArray(32).also { rng.nextBytes(it) }
            val rk   = generateRoundKeys(String(key, Charsets.ISO_8859_1), 32)
            val buf2 = ByteArray(size).also { rng.nextBytes(it) }
            val padded = addPadding(buf2)
            val enc  = ByteArray(minOf(padded.size, size))
            val bc   = enc.size / BLOCKSIZE
            for (i in 0 until bc) {
                val block = padded.copyOfRange(i * BLOCKSIZE, (i + 1) * BLOCKSIZE)
                encBlock(block, rk)
                System.arraycopy(block, 0, enc, i * BLOCKSIZE, BLOCKSIZE)
            }
            raf.seek(0); raf.write(enc, 0, minOf(enc.size, size))

            // Pass3: ビットパターンで上書き + 検証
            val pattern = byteArrayOf(0x92.toByte(), 0x49.toByte(), 0x24.toByte())
            val buf3    = ByteArray(size) { i -> pattern[i % 3] }
            raf.seek(0); raf.write(buf3)
            raf.seek(0)
            val verify  = ByteArray(size)
            raf.readFully(verify)
            if (!verify.contentEquals(buf3)) {
                raf.close()
                return false
            }

            // Pass4: CSPRNG乱数で上書き
            val buf4 = ByteArray(size).also { rng.nextBytes(it) }
            raf.seek(0); raf.write(buf4)

            raf.close()
            file.delete()
            true
        } catch (e: Exception) {
            android.util.Log.e("CryptoService", "shredFile: ${e.message}")
            false
        }
    }
}
