import hashlib


def fingerprint_document(file_bytes: bytes) -> str:
    if file_bytes is None:
        raise ValueError("file_bytes cannot be None")
    return hashlib.sha256(file_bytes).hexdigest()
