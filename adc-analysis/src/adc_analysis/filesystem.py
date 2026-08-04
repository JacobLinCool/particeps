"""Small fail-closed filesystem primitives for plaintext artifacts."""

from __future__ import annotations

import ctypes
import errno
import os
import stat
import sys
from pathlib import Path

from .errors import ValidationError


def private_directory(path: Path) -> Path:
    """Create or tighten a non-symlink directory to owner-only access."""

    path = Path(path).absolute()
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink() or not path.is_dir():
        raise ValidationError("private staging path must be a real directory")
    if os.name == "posix":
        os.chmod(path, 0o700)
        mode = stat.S_IMODE(path.stat(follow_symlinks=False).st_mode)
        if mode != 0o700:
            raise ValidationError("private staging directory permissions are unsafe")
    return path.resolve()


def rename_noreplace(source: Path, destination: Path) -> None:
    """Atomically publish a directory without replacing a concurrent destination."""

    source_bytes = os.fsencode(source)
    destination_bytes = os.fsencode(destination)
    if sys.platform == "darwin":
        libc = ctypes.CDLL(None, use_errno=True)
        renamex_np = libc.renamex_np
        renamex_np.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        renamex_np.restype = ctypes.c_int
        result = renamex_np(source_bytes, destination_bytes, 0x00000004)
    elif sys.platform.startswith("linux"):
        libc = ctypes.CDLL(None, use_errno=True)
        renameat2 = getattr(libc, "renameat2", None)
        if renameat2 is None:
            raise ValidationError("atomic create-only publication is unsupported")
        renameat2.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        renameat2.restype = ctypes.c_int
        result = renameat2(-100, source_bytes, -100, destination_bytes, 1)
    elif os.name == "nt":
        try:
            os.rename(source, destination)
            return
        except FileExistsError as error:
            raise ValidationError("dataset destination already exists") from error
    else:
        raise ValidationError("atomic create-only publication is unsupported")
    if result == 0:
        return
    error_number = ctypes.get_errno()
    if error_number in {errno.EEXIST, errno.ENOTEMPTY}:
        raise ValidationError("dataset destination already exists")
    raise OSError(error_number, os.strerror(error_number), str(destination))
