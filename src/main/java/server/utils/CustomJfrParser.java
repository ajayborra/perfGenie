/*
 * Copyright (c) 2022, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package server.utils;

import com.google.common.base.Stopwatch;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.IMCThread;
import org.openjdk.jmc.common.item.*;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.ITypedQuantity;
import org.openjdk.jmc.common.unit.IUnit;
import org.openjdk.jmc.common.unit.LinearUnit;
import org.openjdk.jmc.flightrecorder.CouldNotLoadRecordingException;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;


import static com.google.common.base.Preconditions.checkNotNull;
import static org.openjdk.jmc.flightrecorder.JfrAttributes.EVENT_STACKTRACE;

/**
 * This class is used for parsing JFR Method samples and to generate Json format
 * it uses JMC toolkit to traverse JFR content.
 */

public class CustomJfrParser {
    private static final Logger logger = LoggerFactory.getLogger(CustomJfrParser.class);
    // only parse 2 jfr concurrently + 10 max in the queue
    private final ExecutorService executor;
    private double threshold = 0.005;
    long duration = 600000000000L; //include samples of duration 10min starting epoch
    final Config config = new Config();

    public CustomJfrParser(final int maxParallelAllowed) {
        System.out.println(Utils.toJson(config));

        executor = new ThreadPoolExecutor(
                1, maxParallelAllowed,
                300L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void setThreshold(final double threshold) {
        this.threshold = threshold;
    }

    public void setDuration(final long duration) {
        this.duration = duration;
    }

    public EventHandler parseStream(final EventHandler builder, final ByteArrayInputStream stream) throws IOException {
        try {
            return executor.submit(() -> doParseStream(builder, stream)).get();
        } catch (RejectedExecutionException e) {
            logger.warn("JFR parser is busy please try after some time");
            throw new IOException("JFR parser is busy please try after some time");
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException(e);
        }
    }

    public EventHandler parseStream(final EventHandler builder, final String path) throws IOException {
        try {
            return executor.submit(() -> doParseStream(builder, path)).get();
        } catch (RejectedExecutionException e) {
            logger.warn("JFR parser is busy please try after some time");
            throw new IOException("JFR parser is busy please try after some time");
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException(e);
        }
    }

    public EventHandler doParseStream(final EventHandler handler, final String path) throws IOException {
        checkNotNull(handler, "null/empty builder argumet");
        checkNotNull(path, "null/empty path argumet");
        try {
            final Stopwatch timer = Stopwatch.createStarted();
            IItemCollection events = JfrLoaderToolkit.loadEvents(new File(path));
            processJfrEvents(handler, events);
            logger.info("doParseStream parse time sec: {}", timer.stop().elapsed(TimeUnit.SECONDS));
            return handler;
        } catch (CouldNotLoadRecordingException e) {
            throw new RuntimeException(e);
        }
    }

    public EventHandler doParseStream(final EventHandler handler, final ByteArrayInputStream stream) throws IOException {
        checkNotNull(handler, "null/empty builder argumet");
        checkNotNull(stream, "null/empty stream argumet");

        try {
            final Stopwatch timer = Stopwatch.createStarted();
            IItemCollection events = JfrLoaderToolkit.loadEvents(stream);
            processJfrEvents(handler, events);
            logger.info("doParseStream parse time sec: {}", timer.stop().elapsed(TimeUnit.SECONDS));
            return handler;
        } catch (CouldNotLoadRecordingException e) {
            throw new RuntimeException(e);
        }
    }

    void processJfrEvents(final EventHandler handler, final IItemCollection events){
        final StringBuilder sb = new StringBuilder();
        final Map<String, List> header = new HashMap<>();
        for (IItemIterable iterable_element : events) {
            if (config.isProfile(iterable_element.getType().getIdentifier())) {
                Object[] r = iterable_element.get().toArray();
                Map k = iterable_element.getType().getAccessorKeys();
                int tid = -1;
                long epoc = -1;
                handler.initializeProfile(iterable_element.getType().getIdentifier());
                handler.initializePid(iterable_element.getType().getIdentifier());
                System.out.println(iterable_element.getType().getIdentifier());
                final IMemberAccessor<IMCStackTrace, IItem> accessor = iterable_element.getType()
                        .getAccessor(EVENT_STACKTRACE.getKey());
                for (final IItem item : iterable_element) {
                    final IMCStackTrace stackTrace = accessor.getMember(item);
                    for (Object key : k.keySet()) {
                        if (((Attribute) key).getContentType().getIdentifier().equals("thread")) {
                            final IMCThread thread = (IMCThread) iterable_element.getType().getAccessor((IAccessorKey) key).getMember(item);
                            tid = thread.getThreadId().intValue();
                        } else if (((Attribute) key).getContentType().getIdentifier().equals("timestamp")) {
                            ITypedQuantity<LinearUnit> v = (ITypedQuantity<LinearUnit>) iterable_element.getType().getAccessor((IAccessorKey) key).getMember(item);
                            epoc = v.longValue();
                        }
                    }
                    handler.processEvent(sb, stackTrace, iterable_element.getType().getIdentifier(), tid, epoc);
                }
            } else if (config.isCustomEvent(iterable_element.getType().getIdentifier())) {
                List l = iterable_element.getType().getAttributes();
                Map k = iterable_element.getType().getAccessorKeys();
                Object[] r = iterable_element.get().toArray();
                boolean addHeader = false;
                if (!header.containsKey(iterable_element.getType().getIdentifier())) {
                    header.put(iterable_element.getType().getIdentifier(), new ArrayList<String>());
                    addHeader = true;
                }

                for (int i = 0; i < r.length; i++) {
                    IUnit u = null;
                    List<Object> record = new ArrayList<>();
                    int tid = -1;
                    for (Object key : k.keySet()) {
                        if (((Attribute) key).getContentType().getIdentifier().equals("thread")) {
                            final IMCThread thread = (IMCThread) iterable_element.getType().getAccessor((IAccessorKey) key).getMember(r[i]);
                            tid = thread.getThreadId().intValue();
                            tid = thread.getThreadId().intValue();
                            record.add(thread.getThreadId());
                            record.add(thread.getThreadName());
                            if (addHeader) {
                                header.get(iterable_element.getType().getIdentifier()).add("tid:text");
                                header.get(iterable_element.getType().getIdentifier()).add("threadname:text");
                            }
                        } else if (((Attribute) key).getContentType().getIdentifier().equals("timestamp")) {
                            ITypedQuantity<LinearUnit> v = (ITypedQuantity<LinearUnit>) iterable_element.getType().getAccessor((IAccessorKey) key).getMember(r[i]);
                            u = v.getUnit();
                            record.add(Math.round(v.longValue() / 1000000d));
                            if (addHeader) {
                                header.get(iterable_element.getType().getIdentifier()).add("timestamp:timestamp");
                            }
                        } else if (((Attribute) key).getContentType().getIdentifier().equals("timespan")) {
                            ITypedQuantity<LinearUnit> v = (ITypedQuantity<LinearUnit>) iterable_element.getType().getAccessor((IAccessorKey) key).getMember(r[i]);
                            record.add((long) Math.round(v.getUnit().valueTransformTo(u.getDeltaUnit()).getMultiplier() * v.longValue() / 1000000d)); //convert to ms
                            if (addHeader) {
                                header.get(iterable_element.getType().getIdentifier()).add("duration:number");
                            }
                        } else if (((Attribute) key).getContentType().getIdentifier().equals("text")) {
                            record.add(iterable_element.getType().getAccessor((IAccessorKey) key).getMember(r[i]));
                            if (addHeader) {
                                header.get(iterable_element.getType().getIdentifier()).add(((Attribute) key).getIdentifier() + ":text");
                            }
                        } else if (((Attribute) key).getContentType().getIdentifier().equals("number")) {
                            record.add(((IQuantity) iterable_element.getType().getAccessor((IAccessorKey) key).getMember(r[i])).longValue());
                            if (addHeader) {
                                header.get(iterable_element.getType().getIdentifier()).add(((Attribute) key).getIdentifier() + ":number");
                            }
                        }
                    }
                    if (addHeader) {
                        handler.initializeEvent(iterable_element.getType().getIdentifier());
                        handler.addHeader(iterable_element.getType().getIdentifier(), header.get(iterable_element.getType().getIdentifier()));
                        addHeader = false;
                    }
                    handler.processContext(record, tid, iterable_element.getType().getIdentifier());
                }
            }
        }
    }


    public static class Config {
        public List<String> getProfiles() {
            return profiles;
        }

        public void setProfiles(List<String> profiles) {
            this.profiles = profiles;
        }

        public List<String> getCustomevents() {
            return customevents;
        }

        public void setCustomevents(List<String> customevents) {
            this.customevents = customevents;
        }

        List<String> profiles = Arrays.asList("ExecutionS", "Socket");
        List<String> customevents = Arrays.asList("LogContext", "MqFrm", "CPUEvent", "MemoryEvent");

        public boolean isCustomEvent(String type) {
            for (int i = 0; i < customevents.size(); i++) {
                if (type.contains(customevents.get(i))) {
                    return true;
                }
            }
            return false;
        }

        public boolean isProfile(String type) {
            for (int i = 0; i < profiles.size(); i++) {
                if (type.contains(profiles.get(i))) {
                    return true;
                }
            }
            return false;
        }
    }
}
