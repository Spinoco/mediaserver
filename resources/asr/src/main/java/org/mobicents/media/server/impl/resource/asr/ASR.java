package org.mobicents.media.server.impl.resource.asr;

import org.mobicents.media.server.component.audio.AudioOutput;
import org.mobicents.media.server.impl.AbstractSink;
import org.mobicents.media.server.spi.pooling.PooledObject;

/** Class representing a implementation of ASR. **/
public abstract class ASR extends AbstractSink implements PooledObject {

    public ASR(String name) { super(name); }

    /**
     * Configure ASR with data from MGCP request.
     *
     * @param asrLang   The language in which the ASR should detect.
     * @param endOfSpeechSilence    The silence amount that is required for us to decide input is recognized.
     * @param  initialSilence       The amount of initial silence that is allowed before we terminate the recognition
     *                              with no speech recognized.
     */
    public abstract void configure(String asrLang, long endOfSpeechSilence, long initialSilence);

    /**
     * Add listener for detected speech.
     *
     * @param listener  The speech detection listener.
     */
    public abstract void addListener(ASRListener listener);

    /**
     * Remove listener for detected speech.
     *
     * @param listener  The speech detection listener.
     */
    public abstract void removeListener(ASRListener listener);

    /**
     * Remove all listener.
     */
    public abstract void clearAllListeners();

    /**
     * The audio output which is to be connected to the main media stream
     * onver which we later on detect the speech.
     */
    public abstract AudioOutput getAudioOutput();
}
