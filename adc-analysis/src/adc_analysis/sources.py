"""Ciphertext-only source adapters."""

from __future__ import annotations

from collections.abc import Iterable
from contextlib import closing
from pathlib import Path
from typing import Protocol
from urllib.parse import urlsplit

from .errors import ValidationError
from .models import SourceObject


class BundleSource(Protocol):
    """Enumerates bounded ciphertext objects without interpreting their contents."""

    def objects(self) -> Iterable[SourceObject]: ...


class LocalBundleSource:
    def __init__(self, paths: Iterable[Path]):
        self.paths = tuple(Path(path).resolve() for path in paths)
        if not self.paths:
            raise ValidationError("at least one local path is required")

    def objects(self) -> Iterable[SourceObject]:
        files: set[Path] = set()
        for path in self.paths:
            if path.is_file():
                files.add(path)
            elif path.is_dir():
                files.update(item for item in path.rglob("*.adcexp") if item.is_file())
            else:
                raise ValidationError(f"local source does not exist: {path}")
        for path in sorted(files, key=str):
            size = path.stat().st_size
            yield SourceObject(
                path.as_uri(), size, None, lambda path=path: path.open("rb")
            )


class S3BundleSource:
    """S3-compatible/R2 reader. Credentials are resolved by boto3, never stored here."""

    def __init__(
        self,
        bucket: str,
        *,
        prefix: str = "",
        endpoint_url: str | None = None,
        region_name: str | None = None,
        profile_name: str | None = None,
        client=None,
    ):
        if not bucket:
            raise ValidationError("S3 bucket is required")
        if endpoint_url is not None:
            try:
                endpoint = urlsplit(endpoint_url)
                hostname = endpoint.hostname
            except ValueError as error:
                raise ValidationError("S3 endpoint must be HTTPS") from error
            if endpoint.scheme != "https" or not hostname:
                raise ValidationError("S3 endpoint must be HTTPS")
        self.bucket = bucket
        self.prefix = prefix
        if client is None:
            import boto3

            session = boto3.Session(profile_name=profile_name)
            client = session.client(
                "s3", endpoint_url=endpoint_url, region_name=region_name
            )
        self.client = client

    def objects(self) -> Iterable[SourceObject]:
        paginator = self.client.get_paginator("list_objects_v2")
        pages = paginator.paginate(Bucket=self.bucket, Prefix=self.prefix)
        for page in pages:
            for item in sorted(
                page.get("Contents", []), key=lambda value: value["Key"]
            ):
                key = item["Key"]
                if key.endswith("/"):
                    continue
                head = self.client.head_object(Bucket=self.bucket, Key=key)
                size = int(head["ContentLength"])
                metadata = {str(k): str(v) for k, v in head.get("Metadata", {}).items()}

                def opener(key=key):
                    response = self.client.get_object(Bucket=self.bucket, Key=key)
                    return closing(response["Body"])

                yield SourceObject(
                    f"s3://{self.bucket}/{key}",
                    size,
                    metadata,
                    opener,
                    source_kind="receiver",
                )
