# SPDX-License-Identifier: MPL-2.0
"""The ordinary Rhino map methods on the canonical Python map objects."""

from collections.abc import Mapping

from ._result_types import ContractError

_MISSING = object()


class ScriptMap(dict):
    def __init__(self, name, values, writable, maps, logger, aliases=None):
        dict.__init__(self, values)
        self._name = name
        self._writable = writable
        self._maps = maps
        self._logger = logger
        self._aliases = aliases or {}

    def _write(self):
        if not self._writable:
            raise TypeError("%s is read-only" % self._name)

    def __getitem__(self, key):
        value = dict.get(self, key, _MISSING)
        if self._name == "responseMap" and (value is _MISSING or value is None) and key in self._aliases:
            value = dict.get(self, self._aliases[key], value)
        if value is _MISSING and self._name == "channelMap":
            source = self._maps.get("sourceMap", {})
            if key in source:
                self._logger.error(
                    'The source map entry "%s" was retrieved from the channel map. '
                    "This method of retrieval has been deprecated and will soon be removed. "
                    "Please use sourceMap.get('%s') instead.", str(key), str(key)
                )
                return source[key]
        if value is _MISSING:
            raise KeyError(key)
        return value

    def __contains__(self, key):
        return self.containsKey(key)

    def __setitem__(self, key, value):
        self._write()
        if not isinstance(key, str):
            raise ContractError("%s requires string keys" % self._name, self._name)
        if self._name in ("globalMap", "globalChannelMap") and value is None:
            raise ContractError("%s[%r] cannot be None; remove the key instead" % (self._name, key), self._name)
        dict.__setitem__(self, key, value)

    def __delitem__(self, key):
        self._write()
        dict.__delitem__(self, key)

    def get(self, key, default=None):
        try:
            return self[key]
        except KeyError:
            return default

    def containsKey(self, key):
        return dict.__contains__(self, key) or (
            self._name == "responseMap" and key in self._aliases
            and dict.__contains__(self, self._aliases[key])
        )

    def containsValue(self, value):
        return value in dict.values(self)

    def put(self, key, value):
        previous = dict.get(self, key)
        self[key] = value
        return previous

    def remove(self, key):
        self._write()
        return dict.pop(self, key, None)

    def putAll(self, values):
        if not isinstance(values, Mapping):
            raise TypeError("putAll requires a map")
        self.update(values)

    def size(self):
        return len(self)

    def isEmpty(self):
        return not self

    def keySet(self):
        return dict.keys(self)

    def entrySet(self):
        return _Entries(self)

    def clear(self):
        self._write()
        dict.clear(self)

    def update(self, values=(), **kwargs):
        self._write()
        items = values.items() if hasattr(values, "items") else values
        for key, value in items:
            self[key] = value
        for key, value in kwargs.items():
            self[key] = value

    def setdefault(self, key, default=None):
        self._write()
        if not dict.__contains__(self, key):
            self[key] = default
        return dict.__getitem__(self, key)

    def pop(self, key, default=_MISSING):
        self._write()
        if default is _MISSING:
            return dict.pop(self, key)
        return dict.pop(self, key, default)

    def popitem(self):
        self._write()
        return dict.popitem(self)

    def __ior__(self, values):
        self.update(values)
        return self


class GlobalMap(ScriptMap):
    # Stock GlobalVariableStore and GlobalChannelVariableStore use void methods.
    def put(self, key, value):
        super().put(key, value)

    def remove(self, key):
        super().remove(key)

    def getVariables(self):
        return _Variables(self)


class _Variables(Mapping):
    """A read-only view, as returned by the engine's getVariables()."""
    def __init__(self, values):
        self._values = values

    def __getitem__(self, key):
        return dict.__getitem__(self._values, key)

    def __iter__(self):
        return iter(self._values)

    def __len__(self):
        return len(self._values)

    def containsKey(self, key):
        return key in self._values

    def containsValue(self, value):
        return self._values.containsValue(value)

    def keySet(self):
        return self._values.keySet()

    def entrySet(self):
        return _Entries(self)

    def size(self):
        return len(self)

    def isEmpty(self):
        return not self

    def _reject(self, *args, **kwargs):
        raise TypeError("%s.getVariables() is read-only" % self._values._name)

    put = putAll = remove = clear = __setitem__ = __delitem__ = _reject


class _Entry:
    def __init__(self, owner, key):
        self._owner, self._key = owner, key

    def getKey(self):
        return self._key

    def getValue(self):
        values = self._owner._values if isinstance(self._owner, _Variables) else self._owner
        return dict.__getitem__(values, self._key)

    def setValue(self, value):
        previous = self.getValue()
        self._owner[self._key] = value
        return previous


class _Entries:
    def __init__(self, owner):
        self._owner = owner

    def __iter__(self):
        for key in self._owner:
            yield _Entry(self._owner, key)

    def __len__(self):
        return len(self._owner)

    def size(self):
        return len(self)

    def isEmpty(self):
        return not self
