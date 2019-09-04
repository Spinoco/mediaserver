
#include "opus.h"

#include "jni.h"

#include <errno.h>
#include <stdlib.h>
#include <string>


extern "C" {

    JNIEXPORT jlong JNICALL Java_org_restcomm_media_codec_opus_OpusNative_createEncoder(
      JNIEnv *, jclass
      , jint    // sampling freq
      , jint    // channels
      , jint    // application (OPUS_APPLICATION_VOIP/OPUS_APPLICATION_AUDIO/OPUS_APPLICATION_RESTRICTED_LOWDELAY)
      , jint    // desired initial bitrate
    );


    JNIEXPORT jint Java_org_restcomm_media_codec_opus_OpusNative_encode(
      JNIEnv *, jclass
      , jlong           // encoder (pointer)
      , jshortArray     // PCM samples to encode
      , jbyteArray      // destination array
    );


    JNIEXPORT void Java_org_restcomm_media_codec_opus_OpusNative_destroyEncoder(
      JNIEnv *, jclass
      , jlong           // encoder (pointer)
    );


    JNIEXPORT jlong Java_org_restcomm_media_codec_opus_OpusNative_createDecoder(
          JNIEnv *, jclass
          , jint    // sampling freq
          , jint    // channels
        );


    JNIEXPORT jint Java_org_restcomm_media_codec_opus_OpusNative_decode(
      JNIEnv *, jclass
      , jlong           // encoder (pointer)
      , jbyteArray      // Opus data to decode
      , jshortArray     // Destination PCM samples
      , jint            // FEC flag
    );


    JNIEXPORT void Java_org_restcomm_media_codec_opus_OpusNative_destroyDecoder(
      JNIEnv *, jclass
      , jlong           // encoder (pointer)
    );

}




JNIEXPORT jlong JNICALL Java_org_restcomm_media_codec_opus_OpusNative_createEncoder(
      JNIEnv *env, jclass
      , jint sampleRate
      , jint channels
      , jint applicationType
      , jint bitrate
    ) {

    // store eventually any error
    int err;

    OpusEncoder *encoder = opus_encoder_create(sampleRate, channels, applicationType, &err);

    if (err != 0) {
      // failure occurred, lets raise the exception here
      std::string message ="Failed to instantiate OpusEncoder, err code: ";
      message.append(std::to_string(err));

      env->ThrowNew(env->FindClass("java/lang/Throwable"), message.c_str());
      return ((jlong) 0);
    }

    // set initial bitrate
    err = opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));

    if (err != 0) {
        std::string message = "Failed to instantiate OpusEncoder, err code: ";
        message.append(std::to_string(err));

        opus_encoder_destroy(encoder);
        env->ThrowNew(env->FindClass("java/lang/Throwable"), message.c_str());
        return ((jlong) 0);
    }

   fprintf(stderr, "OPUS: Created Encoder: %p (sampleRate=%i, channels=%i, app=%i, bitRate=%i)\n", encoder, sampleRate, channels, applicationType, bitrate);


    // return pointer of the encoder just by converting it to long
    return ((jlong) encoder);
}


JNIEXPORT jint Java_org_restcomm_media_codec_opus_OpusNative_encode(
  JNIEnv *env, jclass
  , jlong       encoder
  , jshortArray pcm
  , jbyteArray  data
){
    // convert pointer from long
    OpusEncoder *encoderPtr = ((OpusEncoder *)encoder);

    jshort *pcmData = env->GetShortArrayElements(pcm, NULL);
    jsize pcmLen = env->GetArrayLength(pcm);
    jsize dataSize = env->GetArrayLength(data);

    // allocate encoding buffer
    unsigned char encoded[dataSize];

    int encodedBytes = opus_encode(encoderPtr, pcmData, pcmLen, encoded, dataSize);

    env->ReleaseShortArrayElements(pcm, pcmData, 0);

    // update supplied array with number of bytes encoded if encodedBytes > 0
    // note the encodedBytes may return < 0 in case of err
    if (encodedBytes > 0) {
      env->SetByteArrayRegion(data, 0, encodedBytes, (jbyte *) encoded);
    }

    return encodedBytes;
}


JNIEXPORT void Java_org_restcomm_media_codec_opus_OpusNative_destroyEncoder(
  JNIEnv *env, jclass
  , jlong encoder
) {

    // convert pointer from long
    OpusEncoder *encoderPtr = ((OpusEncoder *)encoder);

    fprintf(stderr, "OPUS: Destroy Encoder: %p \n", encoderPtr);

    opus_encoder_destroy(encoderPtr);

}


JNIEXPORT jlong Java_org_restcomm_media_codec_opus_OpusNative_createDecoder(
  JNIEnv *env, jclass
  , jint  sampleRate
  , jint  channels
) {


     // store eventually any error
    int err;

    OpusDecoder *decoder = opus_decoder_create(sampleRate, channels, &err);

    if (err != 0) {
      // failure occurred, lets raise the exception here
      std::string message ="Failed to instantiate OpusDecoder, err code: ";
      message.append(std::to_string(err));

      env->ThrowNew(env->FindClass("java/lang/Throwable"), message.c_str());
      return ((jlong) 0);
    }

    fprintf(stderr, "OPUS: Created Decoder: %p (sampleRate=%i, channels=%i)\n", decoder, sampleRate, channels);

    // return pointer of the decoder just by converting it to long
    return ((jlong) decoder);

}


JNIEXPORT jint Java_org_restcomm_media_codec_opus_OpusNative_decode(
  JNIEnv *env, jclass
  , jlong       decoder
  , jbyteArray  data
  , jshortArray pcm
  , jint        fec
) {


    // convert pointer from long
    OpusDecoder *decoderPtr = ((OpusDecoder *)decoder);

    jbyte *opusData = env->GetByteArrayElements(data, NULL);
    jsize opusLen = env->GetArrayLength(data);

    // size is in shorts, that means PCM samples
    jsize pcmSize = env->GetArrayLength(pcm);

    // allocate decoding buffer
    short decoded[pcmSize];

    int frameSize = opus_decode(decoderPtr, (unsigned char *)opusData, opusLen, decoded, pcmSize, fec);

    env->ReleaseByteArrayElements(data, opusData, 0);

    if (frameSize > 0) {
         env->SetShortArrayRegion(pcm, 0, frameSize, decoded);
    }

    return frameSize;

}


JNIEXPORT void Java_org_restcomm_media_codec_opus_OpusNative_destroyDecoder(
  JNIEnv *env, jclass
  , jlong  decoder
) {
    // convert pointer from long
    OpusDecoder *decoderPtr = ((OpusDecoder *)decoder);

    fprintf(stderr, "OPUS: Destroy Decoder: %p \n", decoderPtr);

    opus_decoder_destroy(decoderPtr);
}

