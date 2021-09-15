package com.ducky.fastvideoframeextraction.decoder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException


/**
 * Created by Duc Ky Ngo on 9/15/2021.
 * duckyngo1705@gmail.com
 */
class FrameExtractor(private val listener: IVideoFrameExtractor) {
    private var isTerminated = false

    private val size = 1
    private val frameCount = Int.MAX_VALUE
    private val SDK_VERSION_INT = android.os.Build.VERSION.SDK_INT

    val VERBOSE = false
    var MAX_FRAMES = 0

    var isPortrait = false
    var savedFrameWidth = 0
    var savedFrameHeight = 0

    /**
     * Terminate the process
     */
    fun terminate() {
        isTerminated = true
    }

    @Throws(IOException::class)
    fun extractFrames(inputFilePath: String) {
        var decoder: MediaCodec? = null
        var outputSurface: CodecOutputSurface? = null
        var extractor: MediaExtractor? = null
        var width: Int
        var height: Int
        try {
            val inputFile: File = File(inputFilePath)

            // Check whether the input file exist or not
            if (!inputFile.canRead()) {
                throw FileNotFoundException("Unable to read $inputFile")
            }

            extractor = MediaExtractor()
            extractor.setDataSource(inputFile.toString())
            val trackIndex = selectTrack(extractor)
            if (trackIndex < 0) {
                throw RuntimeException("No video track found in $inputFile")
            }
            extractor.selectTrack(trackIndex)

            // Checking orientation by degree
            val format = extractor.getTrackFormat(trackIndex)
            val mediaMetadataRetriever = MediaMetadataRetriever()
            mediaMetadataRetriever.setDataSource(inputFile.absolutePath)
            val orientation: Int = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 90
            Log.d(TAG, "Orientation: $orientation")

            // Checking duration by milliseconds
            val duration = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            Log.d(TAG, "Video duration: $duration")

            // We must set MAX_FRAMES for decode
            val FPS = 60
            if (frameCount == Int.MAX_VALUE) {
                MAX_FRAMES = FPS * duration!!.toInt() / 1000
            } else {
                MAX_FRAMES = frameCount
            }
            height = format.getInteger(MediaFormat.KEY_HEIGHT)
            width = format.getInteger(MediaFormat.KEY_WIDTH)
            if (height > MAX_RESOLUTION || width > MAX_RESOLUTION) {
                val ratio = height.toFloat() / width
                if (height > width) {
                    height = MAX_RESOLUTION
                    width = (height / ratio).toInt()
                } else {
                    width = MAX_RESOLUTION
                    height = (ratio * width).toInt()
                }
            }
            Log.d(
                TAG,
                "$height  h : w  $width"
            )


            // Checking video orientation is portrait or landscape
            isPortrait = orientation.toInt() == 90 || orientation.toInt() == 270
            Log.d(TAG, "isPortrait:  $isPortrait")
            if (SDK_VERSION_INT >= 21) {
                if (isPortrait) {
                    savedFrameHeight = (if (width > height) width else height) / size
                    savedFrameWidth = (if (height < width) height else width) / size
                } else {
                    savedFrameHeight = height / size
                    savedFrameWidth = width / size
                }
            } else {
                savedFrameHeight = height / size
                savedFrameWidth = width / size
            }
            if (VERBOSE) {
                Log.d(TAG, "Video size: " + format.getInteger(MediaFormat.KEY_WIDTH) + "x" + format.getInteger(MediaFormat.KEY_HEIGHT))
            }

            // Could use width/height from the MediaFormat to get full-size frames.
            outputSurface = CodecOutputSurface(savedFrameWidth, savedFrameHeight, isPortrait)

            // Create a MediaCodec decoder, and configure it with the MediaFormat from the
            // extractor.  It's very important to use the format from the extractor because
            // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
            val mime = format.getString(MediaFormat.KEY_MIME)
            decoder = MediaCodec.createDecoderByType(mime!!)
            Log.d(TAG, "Mime :  $mime")
            decoder.configure(format, outputSurface.getSurface(), null, 0)
            decoder.start()
            doExtract(extractor, trackIndex, decoder, outputSurface)
        } finally {
            // release everything we grabbed
            if (outputSurface != null) {
                outputSurface.release()
                outputSurface = null
            }
            if (decoder != null) {
                decoder.stop()
                decoder.release()
                decoder = null
            }
            if (extractor != null) {
                extractor.release()
                extractor = null
            }
        }
    }


    /**
     * Select video tracks
     * Return -1 if no track found
     */
    private fun selectTrack(extractor: MediaExtractor): Int {
        // Select the first video track we find, ignore the rest.
        val numTracks = extractor.trackCount
        for (idx in 0 until numTracks) {
            val format = extractor.getTrackFormat(idx)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime!!.startsWith("video/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track $idx ($mime): $format")
                }
                return idx
            }
        }
        return -1
    }

    /**
     * Work loop.
     */
    @Throws(IOException::class)
    fun doExtract(extractor: MediaExtractor, trackIndex: Int, decoder: MediaCodec, outputSurface: CodecOutputSurface) {
        val TIMEOUT_USEC = 10000
        val decoderInputBuffers = decoder.inputBuffers
        val info = MediaCodec.BufferInfo()
        var inputChunk = 0
        var decodeCount = 0
        var totalSavingTimeNs: Long = 0
        var outputDone = false
        var inputDone = false

        if (VERBOSE) Log.d(TAG, "Start extract loop...")
        while (!outputDone && !isTerminated) {

            // Feed more data to the decoder.
            if (!inputDone) {
                val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                if (inputBufIndex >= 0) {
                    val inputBuf = decoderInputBuffers[inputBufIndex]
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuf's position, limit, etc.
                    val chunkSize = extractor.readSampleData(inputBuf, 0)
                    if (chunkSize < 0) {
                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(
                            inputBufIndex, 0, 0, 0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                        if (VERBOSE) Log.d(TAG, "sent input EOS")
                    } else {
                        if (extractor.sampleTrackIndex != trackIndex) {
                            Log.w(TAG, "WEIRD: got sample from track " + extractor.sampleTrackIndex + ", expected " + trackIndex)
                        }
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(
                            inputBufIndex, 0, chunkSize,
                            presentationTimeUs, 0 /*flags*/
                        )
                        if (VERBOSE) {
                            Log.d(TAG, ("submitted frame $inputChunk to dec, size=$chunkSize"))
                        }
                        inputChunk++
                        extractor.advance()
                    }
                } else {
                    if (VERBOSE) Log.d(TAG, "input buffer not available")
                }
            }
            if (!outputDone) {
                val decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (VERBOSE) Log.d(TAG, "no output from decoder available")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not important for us, since we're using Surface
                    if (VERBOSE) Log.d(TAG, "decoder output buffers changed")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    if (VERBOSE) Log.d(TAG, "decoder output format changed: $newFormat")
                } else if (decoderStatus < 0) {
//                    fail("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
                    Log.d(TAG, "doExtract: unexpected result from decoder.dequeueOutputBuffer: $decoderStatus")
                } else { // decoderStatus >= 0
                    if (VERBOSE) Log.d(
                        TAG,
                        ("surface decoder given buffer " + decoderStatus +
                                " (size=" + info.size + ")")
                    )
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (VERBOSE) Log.d(
                            TAG,
                            "output EOS"
                        )
                        outputDone = true
                    }
                    val doRender = (info.size != 0)

                    // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                    // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                    // that the texture will be available before the call returns, so we
                    // need to wait for the onFrameAvailable callback to fire.
                    decoder.releaseOutputBuffer(decoderStatus, doRender)
                    if (doRender) {
                        if (VERBOSE) Log.d(TAG, "Awaiting decode of frame $decodeCount")
                        outputSurface.awaitNewImage()
                        if (isPortrait) {
                            outputSurface.drawImage(false)
                        } else {
                            outputSurface.drawImage(true)
                        }
                        if (decodeCount < MAX_FRAMES) {
                            val startWhen = System.nanoTime()
                            val currentFrame = outputSurface.retrieveFrame(decodeCount, 0)
                            listener.onCurrentFrameExtracted(currentFrame)
                            totalSavingTimeNs += System.nanoTime() - startWhen
                            if(VERBOSE) Log.d(TAG, "$decodeCount / Max: $MAX_FRAMES")
                        }
                        decodeCount++
                    }
                }
            }
        }
        val totalSavedFrames = if ((MAX_FRAMES < decodeCount)) MAX_FRAMES else decodeCount

        if (VERBOSE) Log.d(TAG, ("Total saved frames: $totalSavedFrames  " +
                "| Total time: ${totalSavingTimeNs / 1000000} ms  " +
                "| Each frame took: ${(totalSavingTimeNs / totalSavedFrames / 1000)} us "))

        listener.onAllFrameExtracted(totalSavedFrames, totalSavingTimeNs)
    }

    companion object {
        const val TAG = "FrameExtractor"
        const val MAX_RESOLUTION = 2000
    }
}