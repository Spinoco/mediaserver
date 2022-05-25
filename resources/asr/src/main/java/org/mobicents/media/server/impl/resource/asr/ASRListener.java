package org.mobicents.media.server.impl.resource.asr;

public interface ASRListener {

    /**
     * Invoked when fragment of the language has been recognized
     * @param fragment
     */
    public void notifySpeechRecognition(String fragment);

}
