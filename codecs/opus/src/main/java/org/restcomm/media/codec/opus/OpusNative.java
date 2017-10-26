package org.restcomm.media.codec.opus;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Created by pach on 26/10/17.
 */
public class OpusNative {


    /** Best for most VoIP/videoconference applications where listening quality and intelligibility matter most **/
    public final static int OPUS_APPLICATION_VOIP                   = 2048;

    /** Best for broadcast/high-fidelity application where the decoded audio should be as close as possible to the input **/
    public final static int OPUS_APPLICATION_AUDIO                  = 2049;

    /** Only use when lowest-achievable latency is what matters most. Voice-optimized modes cannot be used. **/
    public final static int OPUS_APPLICATION_RESTRICTED_LOWDELAY    = 2051;



    /** No error **/
    public static final int OPUS_OK                         = 0;

    /** One or more invalid/out of range arguments **/
    public static final int ERR_OPUS_BAD_ARG                = -1;

    /** Not enough bytes allocated in the buffer **/
    public static final int ERR_OPUS_BUFFER_TOO_SMALL       = -2;

    /** An internal error was detected **/
    public static final int ERR_OPUS_INTERNAL_ERROR         = -3;

    /** The compressed data passed is corrupted **/
    public static final int ERR_OPUS_INVALID_PACKET         = -4;

    /** Invalid/unsupported request number **/
    public static final int ERR_OPUS_UNIMPLEMENTED          = -5;

    /** An encoder or decoder structure is invalid or already freed **/
    public static final int ERR_OPUS_INVALID_STATE          = -6;

    /** Memory allocation has failed **/
    public static final int ERR_OPUS_ALLOC_FAIL             = -7;


    private final static Logger log = LogManager.getLogger(OpusNative.class);



    static {
        log.info("Loading OPUS JNI library");
        System.loadLibrary("opus");
        System.loadLibrary("opus_jni");
        log.info("OPUS JNI library loaded OK");
    }




    /**
     * Creates and allocates new native encoder for Opus.
     *
     * This returns a unique long identifying the Encoder. User of this function must assure that
     * resulting encoder is released by call to `destroyEncoder`.
     *
     * Note that this may throw an exception, when creation and initialization of the encoder will fail.
     *
     * @param sampleRate        Sampling rate to opus Encoder. This must be one of 8000, 12000, 16000, 24000, or 48000.
     * @param channels          Number of channels (1, 2) to encode
     * @param application       Application of the Encoder. This may be one of the following:
     *                          OPUS_APPLICATION_VOIP, OPUS_APPLICATION_AUDIO, OPUS_APPLICATION_RESTRICTED_LOWDELAY
     *
     * @param bitRate           Desired bitrate when encoding the audio.
     */
    public static native long createEncoder(int sampleRate, int channels, int application, int bitRate);


    /**
     * Encodes chunk of PCM samples with supplied encoder.
     *
     * Note that supplied array of PCM samples must be completely filled with the samples,
     * i.e. there is no way to use partially set array (offset, size).
     *
     * For the destination array, this must be large enough to accept all encoded data,
     * but actually it may contain less data than its size when encoding completes.
     *
     * This returns number of bytes encoded. If < 0 that indicates error(see ERR_* codes).
     *
     *
     * @param encoder   Id of encoder from the `createEncoder` function
     * @param pcm       Source array with PCM samples to encode
     * @param data      Destination array to hold all encoded data
     */
    public static native int encode(long encoder, short[] pcm, byte[] data);


    /**
     * This will release all resources with supplied encoder.
     * After this is invoked encoder id is invalid and may not be used anymore.
     *
     * @param encoder   Id of encoder from `createEncoder` function
     */
    public static native void destroyEncoder(long encoder);



    /**
     * Creates and allocates new native decoder for Opus.
     *
     * This returns a unique long identifying the Decoder. User of this function must assure that
     * resulting decoder is released by call to `destroyDecoder`.
     *
     * Note that this may throw an exception, when creation and initialization of the decoder will fail.
     *
     * @param sampleRate        Sampling rate to opus Decoder. This must be one of 8000, 12000, 16000, 24000, or 48000.
     * @param channels          Number of channels (1, 2) to decode
     */
    public static native long createDecoder(int sampleRate, int channels);


    /**
     * Encodes chunk of PCM samples with supplied encoder.
     *
     * Note that supplied array of opus data must be completely filled with the opus data,
     * i.e. there is no way to use partially set array (offset, size).
     *
     * For the destination pcm array, this must be large enough to accept all decoded pcm samples,
     * but actually it may contain less samples than its size when decoding completes.
     *
     * This returns number of samples decoded. If < 0 that indicates error.
     *
     *
     * @param decoder   Id of encoder from the `createEncoder` function
     * @param data      Source array of opus data that may be decoded
     * @param pcm       Destiantion array of PCM samples
     */
    public static native int decode(long decoder, byte[] data, short[] pcm);


    /**
     * This will release all resources with supplied decoder.
     * After this is invoked decoder id is invalid and may not be used anymore.
     *
     * @param decoder   Id of decoder from `createDecoder` function
     */
    public static native void destroyDecoder(long decoder);


//    public static void main(String[] args ) {
//        System.out.println("Hello world");
//        long encoderId = createDecoder(48000, 1);
//        System.out.println("Encoder: " + encoderId);
//        destroyDecoder(encoderId);
//    }

}
