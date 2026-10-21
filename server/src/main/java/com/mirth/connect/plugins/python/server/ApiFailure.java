/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.ArrayList;
import java.util.List;

import com.mirth.connect.plugins.python.PythonApi;

/** A request failure with an HTTP status and a stable code. */
public class ApiFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public final int status;
    public final String code;
    public final List<PythonApi.Diagnostic> diagnostics = new ArrayList<>();
    public String operationId;
    public String ownerNodeId;
    public String registryEpoch;
    public Long currentRevision;

    public ApiFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiFailure badRequest(String code, String message) {
        return new ApiFailure(400, code, message);
    }

    public static ApiFailure notFound(String message) {
        return new ApiFailure(404, "NOT_FOUND", message);
    }

    public static ApiFailure conflict(String code, String message) {
        return new ApiFailure(409, code, message);
    }

    public static ApiFailure unavailable(String code, String message) {
        return new ApiFailure(503, code, message);
    }

    public ApiFailure with(List<PythonApi.Diagnostic> diagnostics) {
        this.diagnostics.addAll(diagnostics);
        return this;
    }

    public PythonApi.ApiError toError() {
        PythonApi.ApiError error = new PythonApi.ApiError();
        error.status = status;
        error.code = code;
        error.message = getMessage();
        error.operationId = operationId;
        error.ownerNodeId = ownerNodeId;
        error.registryEpoch = registryEpoch;
        error.currentRevision = currentRevision;
        error.diagnostics.addAll(diagnostics);
        return error;
    }
}
