package org.mobicents.media.server.impl.resource.asr;

import com.microsoft.cognitiveservices.speech.PropertyId;

public interface ASRListener {

    /**
     * Invoked when fragment of the language has been recognized.
     *
     * @param fragment The fragment of recognized speech.
     *                 If this is empty, then this notifies about No match.
     *                 That is the case when the other party did not start speaking within the
     *                 {@link PropertyId.SpeechServiceConnection_InitialSilenceTimeoutMs}
     */
    public void notifySpeechRecognition(String fragment);

    /**
     * Invoked when we are in process of recognizing a fragment. This is emitted over the time until we properly recognize
     * a fragment.
     *
     * @param fragment  The partial recognition.
     */
    public void notifySpeechRecognizing(String fragment);

}
