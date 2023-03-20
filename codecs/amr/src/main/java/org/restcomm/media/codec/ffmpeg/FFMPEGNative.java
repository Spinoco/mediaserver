package org.restcomm.media.codec.ffmpeg;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FFMPEGNative {

    private final static Logger log = LogManager.getLogger(FFMPEGNative.class);


    static {
        log.info("Loading FFMPEGNative JNI library");
        System.loadLibrary("avcodec");
        System.loadLibrary("swresample");
        System.loadLibrary("avutil");
        System.loadLibrary("ffmpeg_jni");
        log.info("FFMPEGNative JNI library loaded OK");
    }

    /**
     * Initializes FFMPEG native decoder for abstract codecs.
     *
     * This returns the pointer to the decoder, which can later be used when decoding data.
     *
     * The user of this function has to call `destroyDecoder`, once they are done using this.
     * Otherwise this will memory leak.
     *
     * @param codecKind The FFMPEG id of coded to use for decoding.
     * @param defaultSampleRate The sample rate of the codec that we assume.
     *                          This can be overridden if ffmpeg has sample rate in the decoder.
     */
    public static native long createDecoder(int codecKind, int defaultSampleRate);

    /**
     * Decodes chuck of encoded data with given decoder.
     *
     * Note that the data provided should be in a format which is acceptable by the underlying coded.
     * The PCM array should be of a size of frame which we support for the internal codec.
     *
     * @param decoder   The pointer to the decoder created in 'createDecoder' function.
     * @param pcm       The array into which we want to receive PCM data.
     * @param data      The data of the coded which we want to decode.
     */
    public static native int decode(long decoder, byte[] pcm, byte[] data);

    /**
     * Destroys given decoder and releases memory.
     *
     * @param decoder   The pointer to the decoder which should be released.
     */
    public static native void destroyDecoder(long decoder);

}
