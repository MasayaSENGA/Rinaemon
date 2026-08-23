package com.rinaemon.pitchshifter.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * In-place radix-2 Cooley-Tukey FFT operating on an interleaved [re0, im0, re1, im1, ...] buffer.
 * Classic implementation (Bernsee's smbFft). `fftFrameSize` must be a power of two.
 */
object FFT {

    fun transform(fftBuffer: DoubleArray, fftFrameSize: Int, sign: Int) {
        var i: Int
        var j: Int
        var bitm: Int
        var le: Int
        var le2: Int
        var k: Int
        var wr: Double
        var wi: Double
        var arg: Double
        var tr: Double
        var ti: Double
        var ur: Double
        var ui: Double

        // Bit reversal permutation.
        i = 2
        while (i < 2 * fftFrameSize - 2) {
            bitm = 2
            j = 0
            while (bitm < 2 * fftFrameSize) {
                if ((i and bitm) != 0) j++
                j = j shl 1
                bitm = bitm shl 1
            }
            if (i < j) {
                var tmp = fftBuffer[i]
                fftBuffer[i] = fftBuffer[j]
                fftBuffer[j] = tmp
                tmp = fftBuffer[i + 1]
                fftBuffer[i + 1] = fftBuffer[j + 1]
                fftBuffer[j + 1] = tmp
            }
            i += 2
        }

        val kmax = (ln(fftFrameSize.toDouble()) / ln(2.0) + 0.5).toInt()
        le = 2
        k = 0
        while (k < kmax) {
            le = le shl 1
            le2 = le shr 1
            ur = 1.0
            ui = 0.0
            arg = PI / (le2 shr 1)
            wr = cos(arg)
            wi = sign * sin(arg)
            j = 0
            while (j < le2) {
                var p1r = j
                var p1i = p1r + 1
                var p2r = p1r + le2
                var p2i = p2r + 1
                i = j
                while (i < 2 * fftFrameSize) {
                    tr = fftBuffer[p2r] * ur - fftBuffer[p2i] * ui
                    ti = fftBuffer[p2r] * ui + fftBuffer[p2i] * ur
                    fftBuffer[p2r] = fftBuffer[p1r] - tr
                    fftBuffer[p2i] = fftBuffer[p1i] - ti
                    fftBuffer[p1r] += tr
                    fftBuffer[p1i] += ti
                    p1r += le
                    p1i += le
                    p2r += le
                    p2i += le
                    i += le
                }
                tr = ur * wr - ui * wi
                ui = ur * wi + ui * wr
                ur = tr
                j += 2
            }
            k++
        }
    }
}
