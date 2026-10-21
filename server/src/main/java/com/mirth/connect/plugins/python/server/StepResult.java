/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.List;

import com.mirth.connect.donkey.model.message.Status;

/**
 * A validated transformer result. The generated wrapper builds the new
 * msg/tmp values first, then calls {@link #commit()} to apply map changes,
 * then assigns msg, tmp and the response fields.
 */
public final class StepResult {

    private final String msgKind;
    private final String msgText;
    private final String tmpKind;
    private final String tmpText;
    private final boolean hasResponse;
    private final Status responseStatus;
    private final String responseStatusMessage;
    private final String responseError;
    private final List<ScriptMaps.Operation> operations;
    private boolean committed;
    private Boolean accepted;
    public boolean isAccepted() {
        if (accepted == null) throw new IllegalStateException("Not a filter result");
        return accepted;
    }
    StepResult accepted(boolean value) { accepted = value; return this; }

    StepResult(String msgKind, String msgText, String tmpKind, String tmpText, boolean hasResponse, Status responseStatus,
            String responseStatusMessage, String responseError, List<ScriptMaps.Operation> operations) {
        this.msgKind = msgKind;
        this.msgText = msgText;
        this.tmpKind = tmpKind;
        this.tmpText = tmpText;
        this.hasResponse = hasResponse;
        this.responseStatus = responseStatus;
        this.responseStatusMessage = responseStatusMessage;
        this.responseError = responseError;
        this.operations = operations;
    }

    public boolean hasMsg() {
        return msgKind != null;
    }

    public String getMsgKind() {
        return msgKind;
    }

    public String getMsgText() {
        return msgText;
    }

    public boolean hasTmp() {
        return tmpKind != null;
    }

    public String getTmpKind() {
        return tmpKind;
    }

    public String getTmpText() {
        return tmpText;
    }

    public boolean hasResponse() {
        return hasResponse;
    }

    public Status getResponseStatus() {
        return responseStatus;
    }

    public String getResponseStatusMessage() {
        return responseStatusMessage;
    }

    public String getResponseError() {
        return responseError;
    }

    public int getMapChangeCount() {
        return operations.size();
    }

    /** Applies the map changes once. */
    public synchronized void commit() {
        if (committed) {
            return;
        }
        committed = true;
        for (ScriptMaps.Operation operation : operations) {
            operation.apply();
        }
    }
}
