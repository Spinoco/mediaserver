/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.media.server.io.sdp.format;

import org.mobicents.media.server.spi.ConnectionKind;
import org.mobicents.media.server.spi.format.AudioFormat;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.utils.Text;

import javax.annotation.Nullable;

/**
 *
 * @author kulikov
 */
public class AVProfile {
	public final static Text AUDIO = new Text("audio");
	public final static Text VIDEO = new Text("video");
	
	public final static int telephoneEventsID=101;
	public final static int telephoneEvent126=126;
    public final static AudioFormat telephoneEvent = FormatFactory.createAudioFormat("telephone-event", 8000);
    static {
        telephoneEvent.setOptions(new Text("0-15"));
    }
    public final static RTPFormats audio = new RTPFormats();
    public final static RTPFormats video = new RTPFormats();
    public final static RTPFormats application = new RTPFormats();

    // Audio formats for when connection (media) is of siprec kind.
    // Note some codecs in these RTP formats can be decoded only, no encoder.
    public final static RTPFormats audioSipRec = new RTPFormats();
    
    private final static RTPFormat pcmu = new RTPFormat(0, FormatFactory.createAudioFormat("pcmu", 8000, 8, 1), 8000);
    private final static RTPFormat pcma = new RTPFormat(8, FormatFactory.createAudioFormat("pcma", 8000, 8, 1), 8000);
    private final static RTPFormat gsm = new RTPFormat(3, FormatFactory.createAudioFormat("gsm", 8000), 8000);

    // NOTE! In SDP we specify clock rate 8000, but in reality the sample rate is 16000. This is due to issue with registration of the profile to IANA....
    private final static RTPFormat g722 = new RTPFormat(9, FormatFactory.createAudioFormat("g722", 16000), 8000);
    private final static RTPFormat g729 = new RTPFormat(18, FormatFactory.createAudioFormat("g729", 8000), 8000);
    private final static RTPFormat l16 = new RTPFormat(97, FormatFactory.createAudioFormat("l16", 8000, 16, 1), 8000);
    private final static RTPFormat dtmf = new RTPFormat(telephoneEventsID, telephoneEvent, 8000);
    private final static RTPFormat dtmf126 = new RTPFormat(telephoneEvent126, telephoneEvent, 8000);
    private final static RTPFormat ilbc = new RTPFormat(102, FormatFactory.createAudioFormat("ilbc", 8000, 16, 1), 8000);
    private final static RTPFormat linear = new RTPFormat(150, FormatFactory.createAudioFormat("linear", 8000, 16, 1), 8000);
    private final static RTPFormat opus = new RTPFormat(111, FormatFactory.createAudioFormat("opus", 48000, 8, 2), 48000);
    private final static RTPFormat amr_nb = new RTPFormat(105, FormatFactory.createAudioFormat("amr", 8000, 8, 1), 8000);
    private final static RTPFormat amr_wb = new RTPFormat(106, FormatFactory.createAudioFormat("amr-wb", 16000, 8, 1), 16000);

    private final static RTPFormat H261 = new RTPFormat(45, FormatFactory.createVideoFormat("h261"));
    private final static RTPFormat H263 = new RTPFormat(34, FormatFactory.createVideoFormat("h263"));
    private final static RTPFormat MP4V_ES = new RTPFormat(96, FormatFactory.createVideoFormat("mp4v-es"));

    static {
        // This is list of codecs which are offed via normal sdp
        // if you want to test some codecs via softphone or hardwarephone
        // uncomment / add codecs here.
        audio.add(opus);
        audio.add(pcma);
        audio.add(pcmu);
//        audio.add(amr_wb);
//        audio.add(amr_nb);
//        audio.add(g722);
//        audio.add(gsm);
//        audio.add(g729);
//        audio.add(l16);
//        audio.add(ilbc);
        audio.add(dtmf);
        audio.add(dtmf126);
    }

    static  {
        // This is a list of codecs which can be recorded via siprec.
        audioSipRec.add(opus);
        audioSipRec.add(amr_wb);
        audioSipRec.add(amr_nb);
        audioSipRec.add(pcma);
        audioSipRec.add(pcmu);
        audioSipRec.add(g722);
//        audio.add(gsm);
//        audio.add(g729);
//        audio.add(l16);
//        audio.add(ilbc);
        audioSipRec.add(dtmf);
        audioSipRec.add(dtmf126);
    }

    static {
        video.add(H261);
        video.add(H263);
        video.add(MP4V_ES);
    }
    
    public static RTPFormat getFormat(int p) {    	
        RTPFormat res = audio.find(p);
        return res == null ? video.find(p) : res;
    }    
    
    public static RTPFormat getFormat(int p, Text mediaType, @Nullable ConnectionKind kind) {
    	RTPFormat res=null;
    	if(mediaType.equals(AUDIO)) {
    		res = audioFormatForKind(kind).find(p);
    	} else if(mediaType.equals(VIDEO)) {
    		res = video.find(p);    		
    	}
    	return res;
    }

    /**
     * Get the formats which are supported by given kind of connection.
     *
     * @param kind  The kind of connection for which to get the formats.
     *              This may be null in case of no specific connection.
     */
    public static RTPFormats audioFormatForKind(@Nullable ConnectionKind kind) {
        if (kind == ConnectionKind.SIPREC) return audioSipRec;
        else return audio;
    }

    /**
     * Resolve RTP format, this will try to resolve dynamic format in case we are in dynamic payload range.
     *
     * @param payloadType   The id of the payload which is being negotiated.
     * @param codecName     The name of the offered codec.
     * @param sampleRate    The sample rate of the offered codec.
     * @param mediaType     The type of media for which we are resolving.
     * @param kind          The kid of the connection for which we are resolving the codecs.
     */
    public static RTPFormat formatForParameters(
        int payloadType
        , String codecName
        , int sampleRate
        , Text mediaType
        , @Nullable ConnectionKind kind
    ) {
        // https://datatracker.ietf.org/doc/html/rfc3551#section-3
        // Only 96 - 127 allows dynamic resolution, otherwise we should carry on with static.
        if (payloadType >= 96 && payloadType <= 127) {
            if (mediaType.equals(AUDIO)) return audioFormatForKind(kind).findByParams(codecName, sampleRate);
            else if (mediaType.equals(VIDEO)) return video.findByParams(codecName, sampleRate);
            else return null;
        } else {
            return getFormat(payloadType, mediaType, kind);
        }
    }
    
    public static boolean isDtmf(RTPFormat format) {
        if(format == null) {
            return false;
        }
        return dtmf.getID() == format.getID() || dtmf126.getID() == format.getID();
    }

    public static boolean isDefaultDtmf(RTPFormat format) {
        if(format == null) {
            return false;
        }
        return dtmf.getID() == format.getID();
    }
}
