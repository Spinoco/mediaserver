package org.mobicents.media.server.impl.resource.asr.azure;

import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat;
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream;
import org.jboss.util.collection.ConcurrentSet;
import org.mobicents.media.ComponentType;
import org.mobicents.media.server.component.audio.AudioOutput;
import org.mobicents.media.server.impl.resource.asr.ASR;
import org.mobicents.media.server.impl.resource.asr.ASRListener;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.memory.Frame;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of our ASR via azure speech recognition.
 */
public class AzureASR extends ASR {

    private final ConcurrentSet<ASRListener> listeners = new ConcurrentSet<ASRListener>();

    private SpeechRecognizer recognizer;
    private PushAudioInputStream push;
    private SpeechConfig config;
    private AudioConfig audioConfig;

    private final AudioOutput output;
    private final AtomicBoolean active;
    private final String azureKey;
    private final String azureRegion;

    public AzureASR(String name, PriorityQueueScheduler scheduler, String azureKey, String azureRegion) {
        super(name);
        this.output = new AudioOutput(scheduler, ComponentType.ASR_COLLECT.getType());
        this.azureKey = azureKey;
        this.azureRegion = azureRegion;
        this.active = new AtomicBoolean(false);

        output.join(this);
    }

    public void configure(String asrLang) {
        // Create new push stream to which we will be writing data for recognition.
        push = PushAudioInputStream.createPushStream(AudioStreamFormat.getWaveFormatPCM(8000L, (short) 16, (short) 1));

        config = SpeechConfig.fromSubscription(azureKey, azureRegion);
        audioConfig = AudioConfig.fromStreamInput(push);
        recognizer = new SpeechRecognizer(config, asrLang, audioConfig);

        recognizer.recognized.addEventListener((o, e) -> {
            for (ASRListener listener: AzureASR.this.listeners) {
                listener.notifySpeechRecognition(e.getResult().getText());
            }
        });
    }

    @Override
    public void onMediaTransfer(Frame frame) throws IOException {
        if (push != null && active.get()) {
            push.write(frame.getData());
        }
    }

    @Override
    public void activate() {
        if (recognizer != null && !active.get()) {
            recognizer.recognizeOnceAsync();
            active.set(true);
            output.start();
        }
    }

    @Override
    public void deactivate() {
        active.set(false);
        this.output.stop();

        if (recognizer != null && active.get()) {
            try {
                recognizer.stopContinuousRecognitionAsync().get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void checkIn() {
        config.close();
        push.close();
        audioConfig.close();
        recognizer.close();

        config = null;
        push = null;
        audioConfig = null;
        recognizer = null;
    }

    @Override
    public void checkOut() {}

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

}
