
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

    fprintf(stderr, "XXXR Create Encoder: %i %i %i %i\n", sampleRate, channels, applicationType, bitrate);

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

   fprintf(stderr, "XXXR Created Encoder: %p\n", encoder);


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

    fprintf(stderr, "XXXR Destroy Encoder: %p \n", encoderPtr);

    opus_encoder_destroy(encoderPtr);

}


JNIEXPORT jlong Java_org_restcomm_media_codec_opus_OpusNative_createDecoder(
  JNIEnv *env, jclass
  , jint  sampleRate
  , jint  channels
) {

    fprintf(stderr, "XXXR Create Decoder: %i %i i\n", sampleRate, channels);


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

    fprintf(stderr, "XXXR Created Decoder: %p\n", decoder);

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

    fprintf(stderr, "XXXR Destroy Decoder: %p \n", decoderPtr);

    opus_decoder_destroy(decoderPtr);
}


/*

#define FRAME_SIZE 480
#define SAMPLE_RATE 48000
#define CHANNELS 1
#define APPLICATION OPUS_APPLICATION_VOIP
#define BITRATE 48000

#define MAX_FRAME_SIZE 6*480
#define MAX_PACKET_SIZE (3*1276)

JavaVM* gJvm;

extern "C" {


  JNIEXPORT void JNICALL Java_org_restcomm_media_codec_opus_OpusJni_initEncoderNative(JNIEnv *, jobject, jstring);

  JNIEXPORT void JNICALL Java_org_restcomm_media_codec_opus_OpusJni_initDecoderNative(JNIEnv *, jobject, jstring);

  JNIEXPORT void JNICALL Java_org_restcomm_media_codec_opus_OpusJni_closeEncoderNative(JNIEnv *, jobject, jstring);

  JNIEXPORT void JNICALL Java_org_restcomm_media_codec_opus_OpusJni_closeDecoderNative(JNIEnv *, jobject, jstring);

  JNIEXPORT jbyteArray JNICALL Java_org_restcomm_media_codec_opus_OpusJni_encodeNative(
    JNIEnv *jni, jobject, jstring, jshortArray);

  JNIEXPORT jshortArray JNICALL Java_org_restcomm_media_codec_opus_OpusJni_decodeNative(
    JNIEnv *jni, jobject, jstring, jbyteArray);

}

void OnHello() {
  void* env = nullptr;
  jint status = gJvm->GetEnv(&env, JNI_VERSION_1_4);
  if (status != JNI_OK)
    return;
  JNIEnv* jni = reinterpret_cast<JNIEnv*>(env);
  jmethodID jOnHelloMid = jni->GetMethodID(
    jni->GetObjectClass(gOpusObserver), "onHello", "()V");
  jni->CallVoidMethod(gOpusObserver, jOnHelloMid);
}

JNIEXPORT void JNICALL Java_org_restcomm_media_codec_opus_OpusJni_sayHelloNative(JNIEnv *, jobject) {
  printf("Hello World - native!\n");
  OnHello();
}

JNIEXPORT void JNICALL Java_org_restcomm_media_codec_opus_OpusJni_initEncoderNative(JNIEnv *env, jobject, jstring jEncoderId) {

  int err;
  const char *encoderId = env->GetStringUTFChars(jEncoderId, NULL);

  OpusEncoder *encoder = opus_encoder_create(SAMPLE_RATE, CHANNELS, APPLICATION, &err);
  if (err < 0) {
    fprintf(stderr, "Failed to create an encoder: %s\n", opus_strerror(err));
    return;
  }

  err = opus_encoder_ctl(encoder, OPUS_SET_BITRATE(BITRATE));
  if (err < 0) {
    fprintf(stderr, "Failed to set bitrate: %s\n", opus_strerror(err));
    return;
  }

  gEncoderMap[encoderId] = encoder;

  env->ReleaseStringUTFChars(jEncoderId, encoderId);
}

JNIEXPORT void JNICALL Java_org_restcomm_media_codec_opus_OpusJni_initDecoderNative(JNIEnv *env, jobject, jstring jDecoderId) {

  int err;
  const char *decoderId = env->GetStringUTFChars(jDecoderId, NULL);

  OpusDecoder *decoder = opus_decoder_create(SAMPLE_RATE, CHANNELS, &err);
  if (err < 0) {
    fprintf(stderr, "Failed to create decoder: %s\n", opus_strerror(err));
    return;
  }

  gDecoderMap[decoderId] = decoder;

  env->ReleaseStringUTFChars(jDecoderId, decoderId);
}

JNIEXPORT void JNICALL Java_org_restcomm_media_codec_opus_OpusJni_closeEncoderNative(JNIEnv *env, jobject, jstring jEncoderId) {

  const char *encoderId = env->GetStringUTFChars(jEncoderId, NULL);

  OpusEncoder *encoder = gEncoderMap[encoderId];

  opus_encoder_destroy(encoder);

  gEncoderMap.erase(encoderId);

  env->ReleaseStringUTFChars(jEncoderId, encoderId);
}

JNIEXPORT void JNICALL Java_org_restcomm_media_codec_opus_OpusJni_closeDecoderNative(JNIEnv *env, jobject, jstring jDecoderId) {

  const char *decoderId = env->GetStringUTFChars(jDecoderId, NULL);

  OpusDecoder *decoder = gDecoderMap[decoderId];

  opus_decoder_destroy(decoder);

  gDecoderMap.erase(decoderId);

  env->ReleaseStringUTFChars(jDecoderId, decoderId);
}

JNIEXPORT jbyteArray JNICALL Java_org_restcomm_media_codec_opus_OpusJni_encodeNative(
  JNIEnv *env, jobject, jstring jEncoderId, jshortArray jPcmData) {

  const char *encoderId = env->GetStringUTFChars(jEncoderId, NULL);

  jshort *pcmData = env->GetShortArrayElements(jPcmData, NULL);
  jsize pcmLen = env->GetArrayLength(jPcmData);

  OpusEncoder *encoder = gEncoderMap[encoderId];

  unsigned char encoded[MAX_PACKET_SIZE];

  int packetSize;
  packetSize = opus_encode(encoder, pcmData, pcmLen, encoded, MAX_PACKET_SIZE);
  if (packetSize < 0) {
    fprintf(stderr, "Encode failed: %s\n", opus_strerror(packetSize));
    return nullptr;
  }

  env->ReleaseStringUTFChars(jEncoderId, encoderId);
  env->ReleaseShortArrayElements(jPcmData, pcmData, 0);

  jbyteArray jOpusData = env->NewByteArray(packetSize);
  env->SetByteArrayRegion(jOpusData, 0, packetSize, (jbyte *)encoded);

  return jOpusData;
}

JNIEXPORT jshortArray JNICALL Java_org_restcomm_media_codec_opus_OpusJni_decodeNative(
  JNIEnv *env, jobject, jstring jDecoderId, jbyteArray jOpusData) {

  const char *decoderId = env->GetStringUTFChars(jDecoderId, NULL);

  jbyte *opusData = env->GetByteArrayElements(jOpusData, NULL);
  jsize opusLen = env->GetArrayLength(jOpusData);

  OpusDecoder *decoder = gDecoderMap[decoderId];

  short decoded[MAX_FRAME_SIZE];

  int frameSize;
  frameSize = opus_decode(decoder, (unsigned char *)opusData, opusLen, decoded, MAX_FRAME_SIZE, 0);
  if (frameSize < 0) {
    fprintf(stderr, "Decoder failed: %s\n", opus_strerror(frameSize));
    return nullptr;
  }

  env->ReleaseStringUTFChars(jDecoderId, decoderId);
  env->ReleaseByteArrayElements(jOpusData, opusData, 0);

  jshortArray jPcmData = env->NewShortArray(frameSize);
  env->SetShortArrayRegion(jPcmData, 0, frameSize, decoded);

  return jPcmData;
}

JNIEXPORT void JNICALL Java_org_restcomm_media_codec_opus_OpusJni_setOpusObserverNative(
  JNIEnv *env, jobject, jobject jObserver) {
  gOpusObserver = env->NewGlobalRef(jObserver);
}

JNIEXPORT void JNICALL Java_org_restcomm_media_codec_opus_OpusJni_unsetOpusObserverNative(
  JNIEnv *env, jobject) {
  env->DeleteGlobalRef(gOpusObserver);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {

  if (!vm) {
    printf("No Java Virtual Machine pointer");
    return -1;
  }

  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**> (&env), JNI_VERSION_1_4) != JNI_OK) {
    printf("Cannot obtain JNI environment");
    return -1;
  }

  gJvm = vm;

  return JNI_VERSION_1_4;
}

*/