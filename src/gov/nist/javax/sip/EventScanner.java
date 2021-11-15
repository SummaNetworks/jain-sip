/*
 * Conditions Of Use
 *
 * This software was developed by employees of the National Institute of
 * Standards and Technology (NIST), an agency of the Federal Government.
 * Pursuant to title 15 Untied States Code Section 105, works of NIST
 * employees are not subject to copyright protection in the United States
 * and are considered to be in the public domain.  As a result, a formal
 * license is not needed to use the software.
 *
 * This software is provided by NIST as a service and is expressly
 * provided "AS IS."  NIST MAKES NO WARRANTY OF ANY KIND, EXPRESS, IMPLIED
 * OR STATUTORY, INCLUDING, WITHOUT LIMITATION, THE IMPLIED WARRANTY OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, NON-INFRINGEMENT
 * AND DATA ACCURACY.  NIST does not warrant or make any representations
 * regarding the use of the software or the results thereof, including but
 * not limited to the correctness, accuracy, reliability or usefulness of
 * the software.
 *
 * Permission to use this software is contingent upon your acceptance
 * of the terms of this agreement
 *
 * .
 *
 */
package gov.nist.javax.sip;

import gov.nist.core.CommonLogger;
import gov.nist.core.LogLevels;
import gov.nist.core.LogWriter;
import gov.nist.core.StackLogger;
import gov.nist.core.ThreadAuditor;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import gov.nist.javax.sip.stack.SIPClientTransaction;
import gov.nist.javax.sip.stack.SIPDialog;
import gov.nist.javax.sip.stack.SIPServerTransaction;
import gov.nist.javax.sip.stack.SIPTransaction;

import java.util.EventObject;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sip.DialogState;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.SipListener;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionState;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.message.Request;
import javax.sip.message.Response;

/* bug fixes SIPQuest communications and Shu-Lin Chen. */

/**
 * Event Scanner to deliver events to the Listener.
 *
 * @version 1.2 $Revision: 1.47 $ $Date: 2010-12-02 22:04:18 $
 *
 * @author M. Ranganathan <br/>
 *
 *
 */
public class EventScanner implements Runnable {

    ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(100);

    private static StackLogger logger = CommonLogger.getLogger(EventScanner.class);

    private boolean isStopped;

    private BlockingQueue<EventWrapper> pendingEvents;

    private AtomicInteger refCount;

    private SipStackImpl sipStack;

    public void incrementRefcount() {
        this.refCount.incrementAndGet();
    }

    public EventScanner(SipStackImpl sipStackImpl) {
        refCount = new AtomicInteger(0);
        this.pendingEvents = new LinkedBlockingQueue<EventWrapper>();
        Thread myThread = new Thread(this);
        // This needs to be set to false else the
        // main thread mysteriously exits.
        myThread.setDaemon(false);

        this.sipStack = sipStackImpl;

        myThread.setName("EventScannerThread");

        myThread.start();

    }

    public void addEvent(EventWrapper eventWrapper) {
        if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG))
            logger.logDebug("addEvent " + eventWrapper);
        // Add the event into the pending events list
        boolean added = pendingEvents.offer(eventWrapper);

        if (!added)
            logger.logWarning("reached queue capacity limit couldn't addEvent " + eventWrapper);
    }

    /**
     * Stop the event scanner. Decrement the reference count and exit the
     * scanner thread if the ref count goes to 0.
     */

    public void stop() {
        if (refCount.get() == 0) {
            isStopped = true;
        }
    }

    /**
     * Brutally stop the event scanner. This does not wait for the refcount to
     * go to 0.
     *
     */
    public void forceStop() {
        this.isStopped = true;
        this.refCount.set(0);
    }

    /**
     * For the non-re-entrant listener this delivers the events to the listener
     * from a single queue. If the listener is re-entrant, then the stack just
     * calls the deliverEvent method above.
     */
    public void run() {
        try {
            // Ask the auditor to monitor this thread
            ThreadAuditor.ThreadHandle threadHandle = null;
            if(sipStack.getThreadAuditor() != null) {
                threadHandle = sipStack.getThreadAuditor().addCurrentThread();
            }

            while (true) {
                EventWrapper eventWrapper = null;

                // There's nothing in the list, check to make sure we
                // haven't
                // been stopped. If we have, then let the thread die.
                if (this.isStopped) {
                    if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG))
                        logger.logDebug(
                                "Stopped event scanner!!");
                    return;
                }

                // We haven't been stopped, and the event list is indeed
                // rather empty. Wait for some events to come along.
                // Send a heart beat to the thread auditor
                if(threadHandle != null) {
                    threadHandle.ping();
                }


                // There are events in the 'pending events list' that need
                // processing. Hold onto the old 'pending Events' list, but
                // make a new one for the other methods to operate on. This
                // tap-dancing is to avoid deadlocks and also to ensure that
                // the list is not modified while we are iterating over it.
                try {
                    eventWrapper = (EventWrapper) pendingEvents.take();
                    EventForScanner eventForScanner = new EventForScanner(sipStack, eventWrapper);
                    logger.logDebug("Calling executor with " + eventWrapper.sipEvent.toString());
                    executor.execute(eventForScanner);
                } catch (InterruptedException ex) {
                    // Let the thread die a normal death
                    if (logger.isLoggingEnabled(LogLevels.TRACE_ERROR))
                        logger.logError("Interrupted!", ex);
                    return;
                } catch (Exception e) {
                    if (logger.isLoggingEnabled()) {
                        logger.logError(
                                "Unexpected exception caught while delivering event -- carrying on bravely", e);
                    }
                }
            } // end While
        } finally {
            if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                if (!this.isStopped) {
                    logger.logFatalError("Event scanner exited abnormally");
                }
            }
        }
    }

}