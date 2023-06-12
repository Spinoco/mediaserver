package org.mobicents.media.server.spi;

import org.mobicents.media.server.utils.Text;

import javax.annotation.Nullable;

public enum ConnectionKind {

    // SIP rec recording kind, these connections should only ever record / receive data.
    SIPREC;

    @Nullable
    public static ConnectionKind valueOf(Text v) {
        if (v.equals(siprec)) {
            return SIPREC;
        }

        return null;
    }

    private final static Text siprec = new Text("siprec");

}
