"""Compatibility helpers for the project's OpenAI client usage."""

from __future__ import annotations

import inspect
from functools import wraps
from typing import Any

import httpx
from openai import OpenAI

_PATCHED = False


def _normalize_proxy_value(proxies: Any) -> Any:
    if not isinstance(proxies, dict):
        return proxies

    for key in ("all://", "https://", "http://", "all", "https", "http"):
        if key in proxies:
            return proxies[key]

    return next(iter(proxies.values()), None)


def _patch_httpx_client_init(client_cls: type[Any]) -> None:
    original_init = client_cls.__init__

    if getattr(original_init, "_openai_httpx_compat", False):
        return

    if "proxies" in inspect.signature(original_init).parameters:
        return

    @wraps(original_init)
    def compat_init(self: Any, *args: Any, proxies: Any = None, **kwargs: Any) -> None:
        if proxies is not None and "proxy" not in kwargs:
            kwargs["proxy"] = _normalize_proxy_value(proxies)

        original_init(self, *args, **kwargs)

    compat_init._openai_httpx_compat = True  # type: ignore[attr-defined]
    client_cls.__init__ = compat_init


def patch_httpx_for_openai() -> None:
    global _PATCHED

    if _PATCHED:
        return

    _patch_httpx_client_init(httpx.Client)
    _patch_httpx_client_init(httpx.AsyncClient)
    _PATCHED = True


def create_openai_client(api_key: str | None) -> OpenAI | None:
    if not api_key:
        return None

    patch_httpx_for_openai()
    return OpenAI(api_key=api_key)
