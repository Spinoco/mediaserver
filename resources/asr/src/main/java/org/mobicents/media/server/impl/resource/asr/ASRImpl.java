package org.mobicents.media.server.impl.resource.asr;

import com.google.api.gax.rpc.ClientStream;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.speech.v1.*;
import com.google.common.base.Charsets;
import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import org.apache.logging.log4j.Logger;
import org.jboss.util.collection.ConcurrentSet;
import org.mobicents.media.ComponentType;
import org.mobicents.media.server.component.audio.AudioOutput;
import org.mobicents.media.server.impl.AbstractSink;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.pooling.PooledObject;
import com.google.api.gax.rpc.ResponseObserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;

/**
 * Allows to transcribe voice for recognition.
 * This is plugged similarly like recorder, but unlike it
 * this will also send events simlarly like DTMF DETECTOR
 */
public class ASRImpl extends AbstractSink implements PooledObject {

    private final static Logger logger = org.apache.logging.log4j.LogManager.getLogger(ASRImpl.class);

    private ConcurrentSet<ASRListener> listeners = new ConcurrentSet<ASRListener>();

    private AudioOutput output;
    private GoogleObserver observer;
    private SpeechClient client ;
    private ClientStream<StreamingRecognizeRequest> clientStream ;

    private RecognitionConfig config;
    private StreamingRecognitionConfig streamingRecognitionConfig;

    // both are used for asynchronously passing the request/configuration

    private ExecutorService googleRunner;
    private Recognizer recognizer;
    private AtomicBoolean active;


    public ASRImpl(String name, PriorityQueueScheduler scheduler, ExecutorService googleRunner) {
        super(name);
        this.output = new AudioOutput(scheduler, ComponentType.ASR_COLLECT.getType());
        output.join(this);
        this.active = new AtomicBoolean(false);
        this.googleRunner = googleRunner;
    }

    /**
     * Invoked at first opportunity to configure google stack for the correct asr
     * @param config            String, base64 encoded json, used to contain RecognitionConfig
     * @param utterance         utterance for the request
     */
    public void configure(String config, boolean utterance) {
        try {
            byte[] jsonData = BaseEncoding.base64().decode(config);

            RecognitionConfig.Builder builder = RecognitionConfig.newBuilder();
            JsonFormat.parser().merge(new String(jsonData, Charsets.UTF_8), builder);
            this.config = builder.build();
            this.streamingRecognitionConfig =
                    StreamingRecognitionConfig.newBuilder()
                    .setConfig(this.config)
                    .setSingleUtterance(utterance)
                    .setInterimResults(true)
                    .build();

        } catch (Throwable t) {
            logger.error("Failed to configure ASR:  " + config, t);
            this.config = null;
            streamingRecognitionConfig = null;
        }
    }

    @Override
    public void onMediaTransfer(Frame frame) throws IOException {

        try {
            if (this.active.get() && clientStream != null) {  // that indicates we have correctly working stream, if this is null some failure ocurred
                StreamingRecognizeRequest request =
                        StreamingRecognizeRequest.newBuilder()
                                .setAudioContent(ByteString.copyFrom(frame.getData()))
                                .build();

                // note this is run asynchronously.
                 this.recognizer.enqueue(request);
            }

        } catch (Throwable t) {
            logger.error("Failed to process frame " + frame, t);
        }
    }

    @Override
    public void activate() {
        // activates client stream
        // this completes successfully only in case the client stream is fully active.

        // this only activates when this is configured properly
        if (!this.active.get() && this.config != null && this.streamingRecognitionConfig != null) {
            try {

                this.client = SpeechClient.create();
                this.observer = new GoogleObserver();
                this.clientStream = client.streamingRecognizeCallable().splitCall(this.observer);
                this.recognizer = new Recognizer();

                StreamingRecognizeRequest request =
                        StreamingRecognizeRequest.newBuilder()
                        .setStreamingConfig(this.streamingRecognitionConfig)
                        .build(); // The first request in a streaming call has to be a config

                // this is synchronous, as this is run in signalling context
                this.recognizer.enqueue(request);

                //mark this as active
                this.active.set(true);

                this.output.start();

            } catch (Throwable t) {
                logger.error("Failed to configure asr", t);
                this.client = null;
                this.observer = null;
                this.clientStream = null;
                if (this.recognizer != null) this.recognizer.drain();
                this.recognizer = null;
                this.active.set(false);
            }
        } else {
            logger.warn("Tries to perform ASR activate, but the ASR is not yet configured: RecognitionConfig: " + this.config + ", streamingConfig: " + this.streamingRecognitionConfig);
        }

    }

    @Override
    public void deactivate() {

        this.active.set(false);

        this.output.stop();

        this.observer = null;

        if (this.clientStream != null) {
            try { this.clientStream.closeSend(); } catch (Throwable t) {};
            this.clientStream = null;
        }

        if (this.client != null) {
            try { this.client.close(); } catch (Throwable t) {};
            this.client = null;
        }

        if (this.recognizer != null) this.recognizer.drain();
        this.recognizer = null;

    }

    @Override
    public void checkIn() {
    }

    @Override
    public void checkOut() {
    }

    public void addListener(ASRListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ASRListener listener) {
        listeners.remove(listener);
    }

    public void clearAllListeners() { listeners.clear(); }

    public AudioOutput getAudioOutput() {
        return this.output;
    }


    private class GoogleObserver implements ResponseObserver<StreamingRecognizeResponse> {
        private AtomicReference<String> buffer = new AtomicReference<>("");

        @Override
        public void onStart(StreamController streamController) {

        }

        private String extract(StreamingRecognitionResult result) {
            if (result.getAlternativesCount() <= 0) return "";
            else {
                SpeechRecognitionAlternative alternative = result.getAlternatives(0);
                return alternative.getTranscript();
            }
        }

        private void notifyListeners(String result) {
            for (ASRListener listener: ASRImpl.this.listeners) {
                listener.notifySpeechRecognition(result);
            }
        }

        @Override
        public void onResponse(StreamingRecognizeResponse streamingRecognizeResponse) {
            // this assumes the most relevant response is always at head.
            // as such we always take whatever is at head and check if that is final response to add it buffer,
            // otherwise we just keep going and sending the most relevant result concatenated with whatever final results we have collected.


            try {

                if (streamingRecognizeResponse.getResultsList().size() > 0) {
                    StreamingRecognitionResult result = streamingRecognizeResponse.getResultsList().get(0);
                    if (result.getIsFinal()) {
                        // append to current result
                        buffer.getAndAccumulate(extract(result), new BinaryOperator<String>() {
                            @Override
                            public String apply(String s, String s2) {
                                if (s.isEmpty()) return(s2);
                                else return(s + " " + s2);
                            }
                        });

                        // buffer was updated, notify all listeners
                        notifyListeners(buffer.get());
                    } else {
                        String current = buffer.get();
                        if (current.isEmpty()) notifyListeners(extract(result));
                        else notifyListeners(buffer.get() + " " + extract(result));
                    }
                }

                List<StreamingRecognitionResult> results = new ArrayList<StreamingRecognitionResult>();
                results.addAll(streamingRecognizeResponse.getResultsList());

                // filter out all probabilities, that we are not interest into ...
                results.sort(new Comparator<StreamingRecognitionResult>() {
                    @Override
                    public int compare(StreamingRecognitionResult o1, StreamingRecognitionResult o2) {
                        if (o1.getIsFinal()) return 1;
                        else if (o2.getIsFinal()) return -1;
                        else return Float.compare(o1.getStability(), o2.getStability());
                    }
                });
                if (results.size() != 0) {
                    StreamingRecognitionResult result = results.get(0);
                    if (result.getAlternativesCount() > 0) {
                        String that = result.getAlternatives(0).toString();

                    }

                }
            } catch (Throwable t) {
                logger.error("Failed too process response from google", t);
            }

        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }

    }


    private class Recognizer implements Runnable {

        private ConcurrentLinkedQueue<StreamingRecognizeRequest> sampleQueue = new ConcurrentLinkedQueue<StreamingRecognizeRequest>();


        public void enqueue(StreamingRecognizeRequest request) {
            sampleQueue.offer(request);
            schedule();
        }

        public void schedule() {
            ASRImpl.this.googleRunner.submit(this);
        }

        public void drain() {
            sampleQueue.clear();
        }

        @Override
        public void run() {
            StreamingRecognizeRequest request = sampleQueue.poll();

            try {

                if (ASRImpl.this.active.get() && clientStream != null && request != null) {  // that indicates we have correctly working stream, if this is null some failure ocurred
                    // this has be fully asynchronous !
                    clientStream.send(request);
                }

            } catch (Throwable t) {
                logger.error("Failed to process request " + request, t);
                // for safety we deactivate once we could send frame so we won't spawn the gcp server
                ASRImpl.this.active.set(false);
            }
        }
    }

}
