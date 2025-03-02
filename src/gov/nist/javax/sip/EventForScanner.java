package gov.nist.javax.sip;

import gov.nist.core.CommonLogger;
import gov.nist.core.LogLevels;
import gov.nist.core.LogWriter;
import gov.nist.core.StackLogger;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import gov.nist.javax.sip.stack.SIPClientTransaction;
import gov.nist.javax.sip.stack.SIPDialog;
import gov.nist.javax.sip.stack.SIPServerTransaction;
import gov.nist.javax.sip.stack.SIPTransaction;

import javax.sip.*;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.util.EventObject;

public class EventForScanner implements Runnable {
    private static StackLogger logger = CommonLogger.getLogger(EventForScanner.class);
    private SipStackImpl sipStack ;
    private EventWrapper eventWrapper;

    public EventForScanner(SipStackImpl sipStack, EventWrapper eventWrapper){
        this.sipStack = sipStack;
        this.eventWrapper = eventWrapper;
    }

    public void deliverEvent(EventWrapper eventWrapper) {
        EventObject sipEvent = eventWrapper.sipEvent;
        if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG))
            logger.logDebug(
                    "sipEvent = " + sipEvent + "source = "
                            + sipEvent.getSource());
        SipListener sipListener = null;

        if (!(sipEvent instanceof IOExceptionEvent)) {
            sipListener = ((SipProviderImpl) sipEvent.getSource()).getSipListener();
        } else {
            sipListener = sipStack.getSipListener();
        }

        if (sipEvent instanceof RequestEvent) {
            deliverRequestEvent((RequestEvent)sipEvent, eventWrapper, sipListener);
        } else if (sipEvent instanceof ResponseEvent) {
            deliverResponseEvent((ResponseEvent)sipEvent, eventWrapper, sipListener);
        } else if (sipEvent instanceof TimeoutEvent) {
            deliverTimeoutEvent((TimeoutEvent) sipEvent, eventWrapper, sipListener);
        } else if (sipEvent instanceof DialogTimeoutEvent) {
            deliverDialogTimeoutEvent((DialogTimeoutEvent) sipEvent, eventWrapper, sipListener);
        } else if (sipEvent instanceof IOExceptionEvent) {
            deliverIOExceptionEvent((IOExceptionEvent)sipEvent, eventWrapper, sipListener);
        } else if (sipEvent instanceof TransactionTerminatedEvent) {
            deliverTransactionTerminatedEvent((TransactionTerminatedEvent)sipEvent, eventWrapper, sipListener);
        } else if (sipEvent instanceof DialogTerminatedEvent) {
            deliverDialogTerminatedEvent((DialogTerminatedEvent) sipEvent, eventWrapper, sipListener);
        } else {

            logger.logFatalError("bad event" + sipEvent);
        }

    }

    private void deliverRequestEvent(RequestEvent sipEvent, EventWrapper eventWrapper, SipListener sipListener) {
        try {
            // Check if this request has already created a
            // transaction
            SIPRequest sipRequest = (SIPRequest) sipEvent
                    .getRequest();

            if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                logger.logDebug(
                        "deliverEvent : "
                                + sipRequest.getFirstLine()
                                + " transaction "
                                + eventWrapper.transaction
                                + " sipEvent.serverTx = "
                                + ((RequestEvent) sipEvent)
                                .getServerTransaction());
            }

            // Discard the duplicate request if a
            // transaction already exists. If the listener chose
            // to handle the request statelessly, then the listener
            // will see the retransmission.
            // Note that in both of these two cases, JAIN SIP will allow
            // you to handle the request statefully or statelessly.
            // An example of the latter case is REGISTER and an example
            // of the former case is INVITE.

            SIPServerTransaction tx = (SIPServerTransaction) sipStack
                    .findTransaction(sipRequest, true);

            if (tx != null && !tx.passToListener()) {

                // JvB: make an exception for a very rare case: some
                // (broken) UACs use
                // the same branch parameter for an ACK. Such an ACK should
                // be passed
                // to the listener (tx == INVITE ST, terminated upon sending
                // 2xx but
                // lingering to catch retransmitted INVITEs)
                if (sipRequest.getMethod().equals(Request.ACK)
                        && tx.isInviteTransaction() &&
                        ( tx.getLastResponseStatusCode() / 100 == 2 ||
                                sipStack.isNon2XXAckPassedToListener())) {

                    if(!sipStack.isNon2XXAckPassedToListener()) {
                        if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG))
                            logger
                                    .logDebug(
                                            "Detected broken client sending ACK with same branch! Passing...");
                    }
                } else {
                    if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG))
                        logger.logDebug(
                                "transaction already exists! " + tx);
                    return;
                }
            } else if (sipStack.findPendingTransaction(sipRequest.getTransactionId()) != null) {
                if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG))
                    logger.logDebug(
                            "transaction already exists!!");

                return;
            } else {
                // Put it in the pending list so that if a repeat
                // request comes along it will not get assigned a
                // new transaction
                SIPServerTransaction st = (SIPServerTransaction) eventWrapper.transaction;
                sipStack.putPendingTransaction(st);
            }

            // Set up a pointer to the transaction.
            sipRequest.setTransaction(eventWrapper.transaction);
            // Change made by SIPquest
            try {

                if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                    logger
                            .logDebug(
                                    "Calling listener "
                                            + sipRequest.getFirstLine());
                    logger.logDebug(
                            "Calling listener " + eventWrapper.transaction);
                }
                if (sipListener != null)
                    sipListener.processRequest((RequestEvent) sipEvent);

                if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                    logger.logDebug(
                            "Done processing Message "
                                    + sipRequest.getFirstLine());
                }
                if (eventWrapper.transaction != null) {

                    SIPDialog dialog = (SIPDialog) eventWrapper.transaction
                            .getDialog();
                    if (dialog != null)
                        dialog.requestConsumed();

                }
            } catch (Exception ex) {
                // We cannot let this thread die under any
                // circumstances. Protect ourselves by logging
                // errors to the console but continue.
                logger.logException(ex);
            }
        } finally {
            if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                logger.logDebug(
                        "Done processing Message "
                                + ((SIPRequest) (((RequestEvent) sipEvent)
                                .getRequest())).getFirstLine());
            }
            if (eventWrapper.transaction != null
                    && ((SIPServerTransaction) eventWrapper.transaction)
                    .passToListener()) {
                ((SIPServerTransaction) eventWrapper.transaction)
                        .releaseSem();
            }

            if (eventWrapper.transaction != null)
                sipStack
                        .removePendingTransaction((SIPServerTransaction) eventWrapper.transaction);
            if (eventWrapper.transaction.getMethod()
                    .equals(Request.ACK)) {
                // Set the tx state to terminated so it is removed from the
                // stack
                // if the user configured to get notification on ACK
                // termination
                eventWrapper.transaction
                        .setState(TransactionState._TERMINATED);
            }
        }
    }

    private void deliverResponseEvent(ResponseEvent responseEvent, EventWrapper eventWrapper, SipListener sipListener) {
        try {

            SIPResponse sipResponse = (SIPResponse) responseEvent
                    .getResponse();
            SIPDialog sipDialog = ((SIPDialog) responseEvent.getDialog());
            try {
                if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                    logger.logDebug(
                            "Calling listener " + sipListener + " for "
                                    + sipResponse.getFirstLine());
                }
                if (sipListener != null) {
                    SIPTransaction tx = eventWrapper.transaction;
                    if (tx != null) {
                        tx.setPassToListener();
                    }
                    sipListener.processResponse(responseEvent);
                }

                /*
                 * If the response for a request within a dialog is a 481
                 * (Call/Transaction Does Not Exist) or a 408 (Request
                 * Timeout), the UAC SHOULD terminate the dialog.
                 */
                if ((sipDialog != null && (sipDialog.getState() == null || !sipDialog
                        .getState().equals(DialogState.TERMINATED)))
                        && (sipResponse.getStatusCode() == Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST || sipResponse
                        .getStatusCode() == Response.REQUEST_TIMEOUT)) {
                    if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                        logger.logDebug(
                                "Removing dialog on 408 or 481 response");
                    }
                    sipDialog.doDeferredDelete();
                }

                /*
                 * The Client tx disappears after the first 2xx response
                 * However, additional 2xx responses may arrive later for
                 * example in the following scenario:
                 *
                 * Multiple 2xx responses may arrive at the UAC for a single
                 * INVITE request due to a forking proxy. Each response is
                 * distinguished by the tag parameter in the To header
                 * field, and each represents a distinct dialog, with a
                 * distinct dialog identifier.
                 *
                 * If the Listener does not ACK the 200 then we assume he
                 * does not care about the dialog and gc the dialog after
                 * some time. However, this is really an application bug.
                 * This garbage collects unacknowledged dialogs.
                 *
                 */
                if (sipResponse.getCSeq().getMethod()
                        .equals(Request.INVITE)
                        && sipDialog != null
                        && sipResponse.getStatusCode() == 200) {
                    if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                        logger.logDebug(
                                "Warning! unacknowledged dialog. " + sipDialog.getState());
                    }
                    /*
                     * If we dont see an ACK in 32 seconds, we want to tear down the dialog.
                     */
                    sipDialog.doDeferredDeleteIfNoAckSent(sipResponse.getCSeq().getSeqNumber());
                }
            } catch (Exception ex) {
                // We cannot let this thread die under any
                // circumstances. Protect ourselves by logging
                // errors to the console but continue.
                logger.logException(ex);
            }
            // The original request is not needed except for INVITE
            // transactions -- null the pointers to the transactions so
            // that state may be released.
            SIPClientTransaction ct = (SIPClientTransaction) eventWrapper.transaction;
            if (ct != null
                    && TransactionState._COMPLETED == ct.getInternalState()
//                    && ct.getOriginalRequest() != null
                    && !ct.getMethod().equals(
                    Request.INVITE)) {
                // reduce the state to minimum
                // This assumes that the application will not need
                // to access the request once the transaction is
                // completed.
                ct.clearState();
            }
            // mark no longer in the event queue.
        } finally {
            if (eventWrapper.transaction != null
                    && eventWrapper.transaction.passToListener()) {
                eventWrapper.transaction.releaseSem();
            }
        }
    }

    private void deliverTimeoutEvent(TimeoutEvent timeoutEvent, EventWrapper eventWrapper, SipListener sipListener) {
        // Change made by SIPquest
        try {
            // Check for null as listener could be removed.
            if (sipListener != null)
                sipListener.processTimeout(timeoutEvent);
        } catch (Exception ex) {
            // We cannot let this thread die under any
            // circumstances. Protect ourselves by logging
            // errors to the console but continue.
            logger.logException(ex);
        }
    }

    private void deliverDialogTimeoutEvent(DialogTimeoutEvent dialogTimeoutEvent, EventWrapper eventWrapper, SipListener sipListener) {
        try {
            // Check for null as listener could be removed.
            if (sipListener != null && sipListener instanceof SipListenerExt) {
                ((SipListenerExt)sipListener).processDialogTimeout(dialogTimeoutEvent);
            } else {
                if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                    logger.logDebug("DialogTimeoutEvent not delivered" );
                }
            }
        } catch (Exception ex) {
            // We cannot let this thread die under any
            // circumstances. Protect ourselves by logging
            // errors to the console but continue.
            logger.logException(ex);
        }
    }

    private void deliverIOExceptionEvent(IOExceptionEvent sipEvent, EventWrapper eventWrapper, SipListener sipListener) {
        try {
            if (sipListener != null)
                sipListener.processIOException(sipEvent);
        } catch (Exception ex) {
            logger.logException(ex);
        }
    }

    private void deliverTransactionTerminatedEvent(TransactionTerminatedEvent sipEvent, EventWrapper eventWrapper, SipListener sipListener) {
        try {
            if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                logger.logDebug(
                        "About to deliver transactionTerminatedEvent");
                logger.logDebug(
                        "tx = " + sipEvent.getClientTransaction());
                logger.logDebug(
                        "tx = " + sipEvent.getServerTransaction());
            }
            if (sipListener != null)
                sipListener.processTransactionTerminated(sipEvent);
        } catch (AbstractMethodError ame) {
            // JvB: for backwards compatibility, accept this
            if (logger.isLoggingEnabled())
                logger.logWarning(
                        "Unable to call sipListener.processTransactionTerminated");
        } catch (Exception ex) {
            logger.logException(ex);
        }
    }

    private void deliverDialogTerminatedEvent(DialogTerminatedEvent sipEvent, EventWrapper eventWrapper, SipListener sipListener) {
        try {
            if (sipListener != null)
                sipListener.processDialogTerminated(sipEvent);
        } catch (AbstractMethodError ame) {
            // JvB: for backwards compatibility, accept this
            if (logger.isLoggingEnabled())
                logger.logWarning(
                        "Unable to call sipListener.processDialogTerminated");
        } catch (Exception ex) {
            logger.logException(ex);
        }
    }

    @Override
    public void run() {
        this.deliverEvent(eventWrapper);
    }
}