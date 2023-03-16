#include "ffmpeg_jni.h"
#include <algorithm>


extern "C" {
  #include <libavutil/frame.h>
  #include <libavutil/mem.h>
  #include <libavcodec/avcodec.h>
  #include <libswresample/swresample.h>
  #include <libavutil/opt.h>
}

// Data describing additional data which were read from the resample buffer which need to be copied 
// into resulting pcm frame.
struct RemainingData {
  uint8_t *flushData; // This is an array.
  int flushSize;
};

struct DecoderData {
  AVCodec const *codec;
  AVCodecContext *context;
  AVCodecParserContext *parser;
  AVPacket *packet;
  AVFrame *frame;
  AVFrame *resample;
  SwrContext *swr_ctx;
  RemainingData *flush;
};


// Requested destination formates
// these are the internal formats in media server.
const enum AVSampleFormat DEST_FORMAT = AV_SAMPLE_FMT_S16;
const int DEST_SAMPLE_RATE = 8000;
const int DEST_SAMPLES_PER_FRAME = 160;
const int DEST_EXPECTED_BYTES = 320;
const int DEST_SAMPLES_PER_MS = 8;


/*
 * Class:     org_restcomm_media_codec_amr_FFMPEGNative
 * Method:    createDecoder
 * Signature: (I)J
 */
JNIEXPORT jlong JNICALL Java_org_restcomm_media_codec_amr_FFMPEGNative_createDecoder (
  JNIEnv *env
  , jclass
  , jint codecId
) {

  struct DecoderData *data = new DecoderData();
  enum AVCodecID codecFromId = (AVCodecID) (int) codecId;

  data -> packet = av_packet_alloc();
  data -> frame = av_frame_alloc();
  data -> resample = av_frame_alloc();

  /* find the required audio decoder */
  data -> codec = avcodec_find_decoder(codecFromId);
  if (!data -> codec) {
      fprintf(stderr, "Codec not found\n");
      return ((jlong) -1);
  }

  data -> parser = av_parser_init(data -> codec -> id);
  if (!data -> parser) {
      fprintf(stderr, "Parser not found\n");
      return ((jlong) -1);
  }

  data -> context = avcodec_alloc_context3(data -> codec);
  if (!data -> context) {
      fprintf(stderr, "Could not allocate audio codec context\n");
      return ((jlong) -1);
  }

  /* open it */
  if (avcodec_open2(data -> context, data -> codec, NULL) < 0) {
      fprintf(stderr, "Could not open codec\n");
      return ((jlong) -1);
  }

  data -> swr_ctx = swr_alloc();
  if (!data -> swr_ctx) {
      fprintf(stderr, "Could not allocate resampler context\n");
      return ((jlong) -1);
  }

  av_opt_set_chlayout(data -> swr_ctx, "in_chlayout", &data -> context -> ch_layout , 0);
  av_opt_set_int(data -> swr_ctx, "in_sample_rate", data -> context -> sample_rate, 0);
  av_opt_set_sample_fmt(data -> swr_ctx, "in_sample_fmt", data -> context -> sample_fmt, 0);

  av_opt_set_chlayout(data -> swr_ctx, "out_chlayout", &data -> context -> ch_layout, 0);
  av_opt_set_int(data -> swr_ctx, "out_sample_rate", DEST_SAMPLE_RATE, 0);
  av_opt_set_sample_fmt(data -> swr_ctx, "out_sample_fmt", DEST_FORMAT, 0);

  /* initialize the resampling context */
  if ((swr_init(data -> swr_ctx)) < 0) {
      fprintf(stderr, "Failed to initialize the resampling context\n");
      return ((jlong) -1);
  }

  data -> resample -> sample_rate = DEST_SAMPLE_RATE;
  data -> resample -> format = DEST_FORMAT;
  data -> resample -> ch_layout = data -> context -> ch_layout;

  data -> flush = NULL;

  return ((jlong) data);
}

// Resample data to internal codec in the transcoded frame.
static int resample(
  DecoderData *data
) {
  int ret;

  // Ensure the incoming frame is correctly 
  data -> frame -> sample_rate = data -> context -> sample_rate;
  data -> frame -> format = data -> context -> sample_fmt;
  data -> frame -> ch_layout = data -> context -> ch_layout;

  ret = swr_convert_frame(data -> swr_ctx, data -> resample, data -> frame);
  if (ret < 0) {
 
    fprintf(stderr, "Error while converting %d \n", ret);
    return ret;
  }

  // Compute the amount of bytes which are now stored in resample frame.
  int copyLength = av_samples_get_buffer_size(NULL, 1, data -> resample -> nb_samples, DEST_FORMAT, 1);

  // Get amount of data stored in buffer, this can happen when changing sample rate. 
  int skew = swr_get_delay(data -> swr_ctx, 1000);


  // We do not have enough data in the resample frame, check if there is skew and try to read it.
  if (copyLength < DEST_EXPECTED_BYTES && skew) {
    int missingSamples = DEST_SAMPLES_PER_FRAME - data -> resample -> nb_samples;
    int skewSamples = skew * DEST_SAMPLES_PER_MS;

    // Do not read more data from the resample buffer than we have space for.
    int readSamples = std::min(missingSamples, skewSamples);
    int flushSize = av_samples_get_buffer_size(NULL, 1, readSamples, DEST_FORMAT, 1);

    // Alocate array for flushed data.
    uint8_t* flushData = new uint8_t[flushSize]();

    int ret = swr_convert(data -> swr_ctx, &flushData, flushSize, NULL, 0);
    if (ret < 0) {
      fprintf(stderr, "Error while flushing from conversion %d \n", ret);
    }

    RemainingData *flush = new RemainingData();
    flush -> flushSize = flushSize;
    flush -> flushData = flushData;

    // Store the flushed data in data, for later copy.
    data -> flush = flush;
  }

  return copyLength;
}

// Decode data from incoming packet. Packet stored in decoder data.
static int decode(
  DecoderData *data
) {
    int ret;
    int sizeInData = 0;

    /* send the packet with the compressed data to the decoder */
    ret = avcodec_send_packet(data -> context, data -> packet);
    if (ret < 0) {
        fprintf(stderr, "Error submitting the packet to the decoder\n");
        return -1;
    }

    if (ret > 1) {
      fprintf(stdout, "Detected multiple frames per media packet %d\n", ret);
    }

    /* read all the output frames (in general there may be any number of them */
    while (ret >= 0) {
        ret = avcodec_receive_frame(data -> context, data -> frame);
        if (ret == 0) {
          // Resample current frame.
          // Memorieze the amount of samples
          sizeInData = resample(data);
        }

        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF)
            return sizeInData;
        else if (ret < 0) {
            fprintf(stderr, "Error during decoding\n");
            return -1;
        }
    }

    return -1;
}

/*
 * Class:     org_restcomm_media_codec_amr_FFMPEGNative
 * Method:    decode
 * Signature: (J[S[B)I
 */
JNIEXPORT jint JNICALL Java_org_restcomm_media_codec_amr_FFMPEGNative_decode(
  JNIEnv *env
  , jclass
  , jlong decoder
  , jbyteArray pcmArray
  , jbyteArray sourceArray
) {

  int ret;
  int frameSize = -1;
  struct DecoderData *data = ((DecoderData *) decoder);

  jbyte *sourceData = env->GetByteArrayElements(sourceArray, NULL);
  jsize sourceDataLength = env->GetArrayLength(sourceArray);

  ret = av_parser_parse2(
    data -> parser
    , data -> context
    , &data->packet->data
    , &data->packet->size
    , ((const uint8_t*) sourceData)
    , sourceDataLength
    , AV_NOPTS_VALUE, AV_NOPTS_VALUE
    , 0
  );


  if (ret < 0) {
      fprintf(stderr, "Error while parsing\n");
      
      // Failed parsing, release data nd return
      env->ReleaseByteArrayElements(sourceArray, sourceData, 0);
      return -1;
  }

  if (
    data->packet->size
  ) {
    // Decode all data and keep the last frame from the output.
    // TODO if we ever have a need to handle multiple frames, this needs to change.
    frameSize = decode(data);

    if (frameSize < 0) {
      fprintf(stderr, "Error while decoding\n");
      env->ReleaseByteArrayElements(sourceArray, sourceData, 0);
      return -1;
    }

    env->SetByteArrayRegion(pcmArray, 0, frameSize, (const jbyte*)(data -> resample -> extended_data[0]));

    // Do we have flushed data?
    if (data -> flush) {
      env->SetByteArrayRegion(pcmArray, frameSize, data -> flush -> flushSize, (const jbyte*)(data -> flush -> flushData));

      // Add flushed data size to returned data.
      frameSize += data -> flush -> flushSize; 

      // clean up flush memory
      delete[] data -> flush ->flushData;
      delete data -> flush;

      // Remove flush from data.
      data -> flush = NULL; 
    }
  }

  // Needs to be at the end of the file
  // releases the array passed from java.
  env->ReleaseByteArrayElements(sourceArray, sourceData, 0);

  return frameSize;
}

/*
 * Class:     org_restcomm_media_codec_amr_FFMPEGNative
 * Method:    destroyDecoder
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_org_restcomm_media_codec_amr_FFMPEGNative_destroyDecoder(
  JNIEnv *
  , jclass
  , jlong decoder
) {

  struct DecoderData *data = ((DecoderData *) decoder);

  avcodec_free_context(&(data -> context));
  av_parser_close(data -> parser);
  av_frame_free(&(data -> frame));
  av_packet_free(&(data -> packet));
  av_frame_free(&(data -> resample));
  swr_free(&(data -> swr_ctx));

  delete data;

  return;
}

