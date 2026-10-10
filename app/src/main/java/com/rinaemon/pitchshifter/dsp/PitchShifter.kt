/*
 * Based on smbPitchShift.cpp / smbFft by Stephan M. Bernsee.
 *
 * COPYRIGHT 1999-2015 Stephan M. Bernsee <s.bernsee@zynaptiq.com>
 *
 * The Wide Open License (WOL)
 *
 * Permission to use, copy, modify, distribute and sell this software and its
 * documentation for any purpose is hereby granted without fee, provided that
 * the above copyright notice and this license appear in all source copies.
 * THIS SOFTWARE IS PROVIDED "AS IS" WITHOUT EXPRESS OR IMPLIED WARRANTY OF
 * ANY KIND. See http://www.dspguru.com/wol.htm for more information.
 */

package com.rinaemon.pitchshifter.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming phase-vocoder pitch shifter for a single audio channel.
 *
 * Port of Stephan M. Bernsee's classic smbPitchShift algorithm
 * (Wide Open License, see the header above;
 * http://blogs.zynaptiq.com/bernsee/pitch-shifting-using-the-ft/),
 * adapted to a stateful, allocation-free-per-call Kotlin class so it can run
 * on a real-time audio thread across successive small buffers.
 *
 * @param sampleRate sample rate of the audio stream in Hz.
 * @param fftFrameSize FFT window size, must be a power of two. Larger values give
 *        smoother pitch shifting at the cost of latency and CPU.
 * @param oversampling how many analysis windows overlap per FFT-frame worth of samples.
 *        Higher values improve quality at the cost of CPU.
 */
class PitchShifter(
    private val sampleRate: Int,
    private val fftFrameSize: Int = 1024,
    private val oversampling: Int = 4
) {
    private val fftFrameSize2 = fftFrameSize / 2
    private val stepSize = fftFrameSize / oversampling
    private val inFifoLatency = fftFrameSize - stepSize
    private val freqPerBin = sampleRate.toDouble() / fftFrameSize
    private val expct = 2.0 * PI * stepSize / fftFrameSize

    private val inFifo = DoubleArray(fftFrameSize)
    private val outFifo = DoubleArray(fftFrameSize)
    private val fftWorksp = DoubleArray(2 * fftFrameSize)
    private val lastPhase = DoubleArray(fftFrameSize2 + 1)
    private val sumPhase = DoubleArray(fftFrameSize2 + 1)
    private val outputAccum = DoubleArray(2 * fftFrameSize)
    private val anaFreq = DoubleArray(fftFrameSize)
    private val anaMagn = DoubleArray(fftFrameSize)
    private val synFreq = DoubleArray(fftFrameSize)
    private val synMagn = DoubleArray(fftFrameSize)

    private var rover = inFifoLatency

    /**
     * Processes [numSamples] samples from [indata] (starting at index 0) into [outdata],
     * applying a pitch shift of [pitchRatio] (1.0 = unchanged, 2.0 = one octave up,
     * 0.5 = one octave down). Both arrays must be at least [numSamples] long.
     * Introduces an inherent latency of `fftFrameSize - stepSize` samples.
     */
    fun process(indata: FloatArray, outdata: FloatArray, numSamples: Int, pitchRatio: Float) {
        val pitchShift = pitchRatio.toDouble()

        for (i in 0 until numSamples) {
            inFifo[rover] = indata[i].toDouble()
            outdata[i] = outFifo[rover - inFifoLatency].toFloat()
            rover++

            if (rover >= fftFrameSize) {
                rover = inFifoLatency

                // Windowing (Hann-ish) + interleave into complex working buffer.
                for (k in 0 until fftFrameSize) {
                    val window = -0.5 * cos(2.0 * PI * k / fftFrameSize) + 0.5
                    fftWorksp[2 * k] = inFifo[k] * window
                    fftWorksp[2 * k + 1] = 0.0
                }

                FFT.transform(fftWorksp, fftFrameSize, -1)

                // Analysis: convert to true frequency / magnitude per bin.
                for (k in 0..fftFrameSize2) {
                    val real = fftWorksp[2 * k]
                    val imag = fftWorksp[2 * k + 1]
                    val magn = 2.0 * sqrt(real * real + imag * imag)
                    val phase = atan2(imag, real)

                    var tmp = phase - lastPhase[k]
                    lastPhase[k] = phase
                    tmp -= k * expct

                    var qpd = (tmp / PI).toInt()
                    if (qpd >= 0) qpd += (qpd and 1) else qpd -= (qpd and 1)
                    tmp -= PI * qpd

                    tmp = oversampling * tmp / (2.0 * PI)
                    tmp = k * freqPerBin + tmp * freqPerBin

                    anaMagn[k] = magn
                    anaFreq[k] = tmp
                }

                // Processing: shift bins according to the pitch ratio.
                synMagn.fill(0.0)
                synFreq.fill(0.0)
                for (k in 0..fftFrameSize2) {
                    val index = (k * pitchShift).toInt()
                    if (index <= fftFrameSize2) {
                        synMagn[index] += anaMagn[k]
                        synFreq[index] = anaFreq[k] * pitchShift
                    }
                }

                // Synthesis: rebuild complex spectrum from shifted magnitude/frequency.
                for (k in 0..fftFrameSize2) {
                    val magn = synMagn[k]
                    var tmp = synFreq[k]
                    tmp -= k * freqPerBin
                    tmp /= freqPerBin
                    tmp = 2.0 * PI * tmp / oversampling
                    tmp += k * expct

                    sumPhase[k] += tmp
                    val phase = sumPhase[k]

                    fftWorksp[2 * k] = magn * cos(phase)
                    fftWorksp[2 * k + 1] = magn * sin(phase)
                }

                for (k in fftFrameSize + 2 until 2 * fftFrameSize) {
                    fftWorksp[k] = 0.0
                }

                FFT.transform(fftWorksp, fftFrameSize, 1)

                for (k in 0 until fftFrameSize) {
                    val window = -0.5 * cos(2.0 * PI * k / fftFrameSize) + 0.5
                    outputAccum[k] += 2.0 * window * fftWorksp[2 * k] / (fftFrameSize2 * oversampling)
                }
                for (k in 0 until stepSize) {
                    outFifo[k] = outputAccum[k]
                }

                System.arraycopy(outputAccum, stepSize, outputAccum, 0, fftFrameSize)

                for (k in 0 until inFifoLatency) {
                    inFifo[k] = inFifo[k + stepSize]
                }
            }
        }
    }
}
