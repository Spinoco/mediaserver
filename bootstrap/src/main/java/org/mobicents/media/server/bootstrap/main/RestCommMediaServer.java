/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2016, Telestax Inc and individual contributors
 * by the @authors tag. 
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

package org.mobicents.media.server.bootstrap.main;

import com.google.inject.Inject;
import org.apache.log4j.Logger;
import org.mobicents.media.server.io.network.UdpManager;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.scheduler.Scheduler;
import org.mobicents.media.server.scheduler.Task;
import org.mobicents.media.server.spi.ControlProtocol;
import org.mobicents.media.server.spi.MediaServer;
import org.mobicents.media.server.spi.ServerManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class RestCommMediaServer implements MediaServer {

    private static final Logger log = Logger.getLogger(RestCommMediaServer.class);

    // Media Server State
    private AtomicBoolean started = new AtomicBoolean(false);
    private Map<ControlProtocol, ServerManager> controllers;

    // Core Components
    private final PriorityQueueScheduler mediaScheduler;
    private final Scheduler taskScheduler;
    private final UdpManager udpManager;

    // Heart beat
    private final HeartBeat heartbeat;
    private final int heartbeatTime;
    private volatile long ttl;

    @Inject
    public RestCommMediaServer(PriorityQueueScheduler mediaScheduler, Scheduler taskScheduler, UdpManager udpManager,
            ServerManager controller) {
        // Core Components
        this.mediaScheduler = mediaScheduler;
        this.taskScheduler = taskScheduler;
        this.udpManager = udpManager;

        // Media Server State
        this.controllers = new HashMap<>(2);
        addManager(controller);

        // Heartbeat
        this.heartbeat = new HeartBeat();
        this.heartbeatTime = 1;
    }

    @Override
    public void addManager(ServerManager manager) {
        if (manager != null) {
            ControlProtocol protocol = manager.getControlProtocol();
            if (this.controllers.containsKey(protocol)) {
                throw new IllegalArgumentException(protocol + " controller is already registered");
            } else {
                this.controllers.put(protocol, manager);
            }
        }
    }

    @Override
    public void removeManager(ServerManager manager) {
        if (manager != null) {
            this.controllers.remove(manager.getControlProtocol());
        }
    }

    @Override
    public void start() throws IllegalStateException {
        if (!this.started.getAndSet(true)) {

            ttl = heartbeatTime * 600;
            mediaScheduler.submitHeartbeat(this.heartbeat);
            this.mediaScheduler.start();
            this.taskScheduler.start();
            this.udpManager.start();
            for (ServerManager controller : this.controllers.values()) {
                controller.activate();
            }

            if (log.isInfoEnabled()) {
                log.info("Media Server started");
            }
        } else {
            throw new IllegalStateException("Media Server already started.");
        }
    }

    @Override
    public void stop() throws IllegalStateException {
        if (this.started.getAndSet(false)) {
            this.udpManager.stop();
            this.taskScheduler.stop();
            this.mediaScheduler.stop();
            for (ServerManager controller : this.controllers.values()) {
                controller.deactivate();
            }
            if (log.isInfoEnabled()) {
                log.info("Media Server stopped");
            }
        } else {
            throw new IllegalStateException("Media Server already stopped.");
        }
    }
    
    @Override
    public boolean isRunning() {
        return this.started.get();
    }

    private final class HeartBeat extends Task {

        public HeartBeat() {
            super();
        }




        @Override
        public void perform() {
            if (started.get()) {
                ttl--;
                if (ttl == 0) {
                    log.info("Global hearbeat is still alive");
                    ttl = heartbeatTime * 600;
                    mediaScheduler.submitHeartbeat(this);
                } else {
                    mediaScheduler.submitHeartbeat(this);
                }
            }
        }
    }

}
