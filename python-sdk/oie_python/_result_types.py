# SPDX-License-Identifier: MPL-2.0
"""Result types and errors shared by scripts and the worker.

The module name avoids CPython 3.14's built-in ``_types``, which static
Python analyzers can confuse with a relative import of that name.
"""

from enum import Enum


class Status(str, Enum):
    """Final Writer statuses accepted by the engine.

    Return a member directly for an empty response, or use it as Response.status.
    Plain strings returned by a Writer remain successful response data.
    """

    SENT = "SENT"
    ERROR = "ERROR"
    QUEUED = "QUEUED"

    def __str__(self):
        return self.value


RESPONSE_STATUSES = ("SENT", "ERROR", "QUEUED")

# Source map keys the engine owns. The Java connector enforces the same list.
RESERVED_SOURCE_MAP_KEYS = frozenset(
    ("destinationSet", "batchId", "batchComplete", "batchSequenceId", "reprocessed", "replaced")
)


class OieError(Exception):
    """Base class for errors raised by the SDK."""


class ContractError(OieError):
    """A script returned or assigned a value outside the supported contract."""

    def __init__(self, message, field=None):
        super().__init__(message)
        self.field = field


class UnsupportedValueError(OieError, TypeError):
    """A script read or changed a value the SDK cannot represent."""


class UnsupportedValue:
    """Stands in for an engine value that has no Python representation.

    The original value stays in the engine. Assigning the sentinel to another
    key copies the original engine value. Any other use raises
    UnsupportedValueError.
    """

    __slots__ = ("_map", "_key", "_type")

    def __init__(self, map_name, key, java_type):
        object.__setattr__(self, "_map", map_name)
        object.__setattr__(self, "_key", key)
        object.__setattr__(self, "_type", java_type)

    @property
    def java_type(self):
        return self._type

    @property
    def origin(self):
        return (self._map, self._key)

    def _fail(self, *args, **kwargs):
        raise UnsupportedValueError(
            "%s[%r] holds an engine value of type %s that Python cannot read. "
            "Convert it to a supported type in a JavaScript step first."
            % (self._map, self._key, self._type)
        )

    def __repr__(self):
        return "<UnsupportedValue %s at %s[%r]>" % (self._type, self._map, self._key)

    def __eq__(self, other):
        return self is other

    def __hash__(self):
        return id(self)

    def __copy__(self):
        return self

    def __deepcopy__(self, memo):
        return self

    def __setattr__(self, name, value):
        self._fail()

    def __getattr__(self, name):
        self._fail()

    __str__ = __bytes__ = __bool__ = __int__ = __float__ = __index__ = _fail
    __iter__ = __len__ = __getitem__ = __setitem__ = __delitem__ = __contains__ = _fail
    __lt__ = __le__ = __gt__ = __ge__ = __add__ = __radd__ = __call__ = _fail


class Message:
    """One message returned by a Python Reader.

    This is the Python counterpart of the engine's script RawMessage. The
    connector creates the Java RawMessage after validating the entire result.
    Set result to one Message, a list of Message objects, or None for no work.

    payload: str or bytes. An empty string or empty bytes is still a message.
    source_map: dict of supported values; defaults to an empty dict.
    destination_ids: None for all enabled destinations, or a list of
    destination metadata IDs. An empty list selects no destinations.

    Fields remain mutable until result extraction. Java objects and engine
    bookkeeping fields (attachments, import/overwrite flags, original ID) are
    not exposed.
    Binary payloads require batch processing to be disabled.
    """

    __slots__ = ("payload", "source_map", "destination_ids")

    def __init__(self, payload, source_map=None, destination_ids=None):
        self.payload = payload
        self.source_map = {} if source_map is None else source_map
        self.destination_ids = destination_ids

    def __repr__(self):
        kind = "bytes" if isinstance(self.payload, (bytes, bytearray)) else type(self.payload).__name__
        return "Message(payload=<%s %d>, destination_ids=%r)" % (
            kind,
            len(self.payload) if isinstance(self.payload, (str, bytes, bytearray)) else -1,
            self.destination_ids,
        )


class Response:
    """Writer response data, status, short status message and error details.

    status accepts Status members or SENT/ERROR/QUEUED strings. Optional text
    fields accept str or None; the engine stores absent fields as empty strings.
    The engine's destination queue, retry and response validation settings apply.
    """

    __slots__ = ("status", "data", "status_message", "error")

    def __init__(self, status="SENT", data=None, status_message=None, error=None):
        self.status = status
        self.data = data
        self.status_message = status_message
        self.error = error

    def __repr__(self):
        return "Response(status=%r, data=%r, status_message=%r, error=%r)" % (
            self.status,
            self.data,
            self.status_message,
            self.error,
        )
