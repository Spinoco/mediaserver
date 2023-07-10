package org.mobicents.media.server.impl.resource.asr;

import com.microsoft.cognitiveservices.speech.PropertyId;

public interface ASRListener {

    /**
     * Invoked when fragment of the language has been recognized.
     *
     * @param fragment The fragment of recognized speech.
     */
    public void notifySpeechRecognition(String fragment);

    /**
     * Invoked when we are in process of recognizing a fragment. This is emitted over the time until we properly recognize
     * a fragment.
     *
     * @param fragment  The partial recognition.
     */
    public void notifySpeechRecognizing(String fragment);

    /**
     * Invoked when the ASR implementation signals that timeout has occurred in the detection.
     * This can be case as with {@link PropertyId.SpeechServiceConnection_InitialSilenceTimeoutMs},
     * when the other party did not start speaking within the given time limit.
     */
    public void notifyEarlyTimeout();

}
