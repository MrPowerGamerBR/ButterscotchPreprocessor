package com.mrpowergamerbr.butterscotchpreprocessor

fun bzip2Decompress(input: ByteArray): ByteArray {
    val br = BitReader(input)
    if (br.readBits(8) != 'B'.code) throw IllegalStateException("Not a bzip2 stream: missing 'B'")
    if (br.readBits(8) != 'Z'.code) throw IllegalStateException("Not a bzip2 stream: missing 'Z'")
    if (br.readBits(8) != 'h'.code) throw IllegalStateException("Not a bzip2 stream: missing 'h'")
    val level = br.readBits(8) - '0'.code
    if (level !in 1..9) throw IllegalStateException("Invalid bzip2 block size level: $level")
    val blockSize = 100000 * level

    var out = ByteArray(maxOf(64, input.size * 4))
    var outLen = 0

    while (true) {
        val magicHi = br.readBits(24)
        val magicLo = br.readBits(24)
        val magic = (magicHi.toLong() shl 24) or (magicLo.toLong() and 0xFFFFFF)
        if (magic == 0x177245385090L) {
            br.readBits(32) // stream CRC, skip
            // there could be another stream concatenated, but we stop here
            break
        }
        if (magic != 0x314159265359L) throw IllegalStateException("Invalid block magic: ${magic.toString(16)}")

        br.readBits(32) // block CRC, skip
        val randomised = br.readBits(1)
        if (randomised != 0) throw IllegalStateException("Randomised blocks not supported")
        val origPtr = br.readBits(24)

        // Symbol map
        val mapL1 = br.readBits(16)
        val seqToUnseq = IntArray(256)
        var numInUse = 0
        for (i in 0 until 16) {
            if ((mapL1 and (1 shl (15 - i))) != 0) {
                val sub = br.readBits(16)
                for (j in 0 until 16) {
                    if ((sub and (1 shl (15 - j))) != 0) {
                        seqToUnseq[numInUse++] = i * 16 + j
                    }
                }
            }
        }
        if (numInUse == 0) throw IllegalStateException("numInUse == 0")
        val alphaSize = numInUse + 2

        val numTrees = br.readBits(3)
        if (numTrees !in 2..6) throw IllegalStateException("Invalid numTrees: $numTrees")
        val numSelectors = br.readBits(15)
        if (numSelectors < 1) throw IllegalStateException("Invalid numSelectors: $numSelectors")

        val selectorsMtf = IntArray(numSelectors)
        for (i in 0 until numSelectors) {
            var j = 0
            while (br.readBits(1) == 1) {
                j++
                if (j >= numTrees) throw IllegalStateException("Selector MTF index out of range")
            }
            selectorsMtf[i] = j
        }

        // Inverse MTF the selectors
        val pos = IntArray(numTrees) { it }
        val selectors = IntArray(numSelectors)
        for (i in 0 until numSelectors) {
            var v = selectorsMtf[i]
            val tmp = pos[v]
            while (v > 0) {
                pos[v] = pos[v - 1]
                v--
            }
            pos[0] = tmp
            selectors[i] = tmp
        }

        // Read code lengths for each tree
        val codeLens = Array(numTrees) { IntArray(alphaSize) }
        for (t in 0 until numTrees) {
            var curr = br.readBits(5)
            for (s in 0 until alphaSize) {
                while (true) {
                    if (curr !in 1..20) throw IllegalStateException("Invalid code length: $curr")
                    if (br.readBits(1) == 0) break
                    if (br.readBits(1) == 1) curr-- else curr++
                }
                codeLens[t][s] = curr
            }
        }

        // Build canonical Huffman decoders
        val limits = Array(numTrees) { IntArray(21) }
        val bases = Array(numTrees) { IntArray(21) }
        val perms = Array(numTrees) { IntArray(alphaSize) }
        val minLens = IntArray(numTrees)
        for (t in 0 until numTrees) {
            val lens = codeLens[t]
            var minLen = 32
            var maxLen = 0
            for (l in lens) {
                if (maxLen > l) {} else maxLen = l
                if (l > minLen) {} else minLen = l
            }
            minLens[t] = minLen
            // perm sorted by (length, original index)
            val perm = perms[t]
            var pp = 0
            for (l in minLen..maxLen) {
                for (s in 0 until alphaSize) {
                    if (lens[s] == l) perm[pp++] = s
                }
            }
            // count of codes per length
            val length = IntArray(maxLen + 2)
            for (s in 0 until alphaSize) length[lens[s]]++
            // base/limit canonical construction
            val base = bases[t]
            val limit = limits[t]
            var code = 0
            var idx = 0
            for (l in minLen..maxLen) {
                val n = length[l]
                limit[l] = code + n - 1
                base[l] = code - idx
                idx += n
                code = (code + n) shl 1
            }
        }

        // Decode MTF/RLE2 stream
        val mtf = IntArray(numInUse) { it }
        val bwtInput = ByteArray(blockSize)
        var bwtLen = 0
        val EOB = numInUse + 1

        var groupPos = 0
        var groupNo = -1
        var currTree = 0
        var runAccum = 0
        var runShift = 0

        while (true) {
            if (groupPos == 0) {
                groupNo++
                if (groupNo >= numSelectors) throw IllegalStateException("Ran out of selectors")
                currTree = selectors[groupNo]
                groupPos = 50
            }
            groupPos--

            val limit = limits[currTree]
            val base = bases[currTree]
            val perm = perms[currTree]
            var len = minLens[currTree]
            var code = br.readBits(len)
            while (code > limit[len]) {
                len++
                if (len >= limit.size) throw IllegalStateException("Huffman decode overflow")
                code = (code shl 1) or br.readBits(1)
            }
            val sym = perm[code - base[len]]

            if (sym == EOB) break

            if (sym == 0 || sym == 1) {
                runAccum += (sym + 1) shl runShift
                runShift++
            } else {
                // Flush any pending run of MTF[0]
                if (runShift > 0) {
                    val b = (seqToUnseq[mtf[0]] and 0xFF).toByte()
                    if (bwtLen + runAccum > blockSize) throw IllegalStateException("BWT block overflow")
                    repeat(runAccum) { bwtInput[bwtLen++] = b }
                    runAccum = 0
                    runShift = 0
                }
                val mtfIdx = sym - 1
                if (mtfIdx >= numInUse) throw IllegalStateException("MTF index out of range")
                val v = mtf[mtfIdx]
                var k = mtfIdx
                while (k > 0) {
                    mtf[k] = mtf[k - 1]
                    k--
                }
                mtf[0] = v
                if (bwtLen >= blockSize) throw IllegalStateException("BWT block overflow")
                bwtInput[bwtLen++] = (seqToUnseq[v] and 0xFF).toByte()
            }
        }
        // Flush trailing run
        if (runShift > 0) {
            val b = (seqToUnseq[mtf[0]] and 0xFF).toByte()
            if (bwtLen + runAccum > blockSize) throw IllegalStateException("BWT block overflow")
            repeat(runAccum) { bwtInput[bwtLen++] = b }
        }

        if (origPtr >= bwtLen) throw IllegalStateException("origPtr out of range: $origPtr >= $bwtLen")

        // Inverse BWT
        val cftab = IntArray(257)
        for (i in 0 until bwtLen) cftab[(bwtInput[i].toInt() and 0xFF) + 1]++
        for (i in 1..256) cftab[i] += cftab[i - 1]
        val tt = IntArray(bwtLen)
        for (i in 0 until bwtLen) {
            val c = bwtInput[i].toInt() and 0xFF
            tt[cftab[c]] = i
            cftab[c]++
        }

        // Inverse RLE-1 while iterating BWT output
        var idx = origPtr
        var remaining = bwtLen
        var lastByte = -1
        var runCount = 0
        while (remaining > 0) {
            idx = tt[idx]
            val b = bwtInput[idx].toInt() and 0xFF
            remaining--

            if (runCount == 4) {
                // b is the count of additional copies (0..251)
                if (b > 251) throw IllegalStateException("RLE-1 count too big: $b")
                if (outLen + b > out.size) out = out.copyOf(maxOf(out.size * 2, outLen + b + 16))
                val lb = lastByte.toByte()
                repeat(b) { out[outLen++] = lb }
                runCount = 0
                lastByte = -1
                continue
            }

            if (outLen >= out.size) out = out.copyOf(out.size * 2)
            out[outLen++] = b.toByte()

            if (b == lastByte) {
                runCount++
            } else {
                runCount = 1
                lastByte = b
            }
        }
    }

    return if (outLen == out.size) out else out.copyOf(outLen)
}

private class BitReader(private val data: ByteArray) {
    private var pos = 0
    private var buf = 0
    private var bitsLeft = 0

    fun readBits(n: Int): Int {
        if (n == 0) return 0
        if (n > 24) {
            val hi = readBits(n - 16)
            val lo = readBits(16)
            return (hi shl 16) or lo
        }
        while (bitsLeft < n) {
            if (pos >= data.size) throw IllegalStateException("Unexpected end of bzip2 stream")
            buf = (buf shl 8) or (data[pos].toInt() and 0xFF)
            pos++
            bitsLeft += 8
        }
        val result = (buf ushr (bitsLeft - n)) and ((1 shl n) - 1)
        bitsLeft -= n
        // Keep buf bounded to avoid sign issues across many reads
        buf = buf and ((1 shl bitsLeft) - 1).let { if (bitsLeft >= 31) -1 else it }
        return result
    }
}
