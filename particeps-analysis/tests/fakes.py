from __future__ import annotations

import io


class FakeS3Client:
    def __init__(self, objects: dict[str, tuple[bytes, dict[str, str]]]):
        self.objects = objects

    def get_paginator(self, name):
        if name != "list_objects_v2":
            raise AssertionError(name)
        return _Paginator(self.objects)

    def head_object(self, *, Bucket, Key):
        _ = Bucket
        data, metadata = self.objects[Key]
        return {"ContentLength": len(data), "Metadata": metadata}

    def get_object(self, *, Bucket, Key):
        _ = Bucket
        data, _ = self.objects[Key]
        return {"Body": io.BytesIO(data)}


class _Paginator:
    def __init__(self, objects):
        self.objects = objects

    def paginate(self, *, Bucket, Prefix):
        _ = Bucket
        return [
            {
                "Contents": [
                    {"Key": key}
                    for key in sorted(self.objects)
                    if key.startswith(Prefix)
                ]
            }
        ]
