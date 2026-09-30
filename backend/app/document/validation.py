from dataclasses import dataclass
from io import BytesIO

from pypdf import PdfReader

from app.core.exceptions import ApplicationException


PDF_SIGNATURE = b"%PDF-"
ALLOWED_CONTENT_TYPES = {"application/pdf"}


@dataclass(frozen=True)
class PdfValidationResult:
    filename: str
    content_type: str | None
    size_bytes: int


def validate_pdf(
    file_bytes: bytes,
    filename: str | None,
    content_type: str | None,
    max_size_bytes: int,
) -> PdfValidationResult:
    if file_bytes is None:
        raise ApplicationException(
            message="No file data was supplied.",
            status_code=400,
            error_code="MISSING_FILE",
        )

    if len(file_bytes) == 0:
        raise ApplicationException(
            message="The uploaded file is empty.",
            status_code=400,
            error_code="EMPTY_FILE",
        )

    if len(file_bytes) > max_size_bytes:
        raise ApplicationException(
            message="The uploaded file exceeds the configured maximum size.",
            status_code=413,
            error_code="FILE_TOO_LARGE",
            details={
                "size_bytes": len(file_bytes),
                "max_size_bytes": max_size_bytes,
            },
        )

    if not filename or not filename.strip():
        raise ApplicationException(
            message="A filename is required.",
            status_code=400,
            error_code="MISSING_FILENAME",
        )

    clean_filename = filename.strip()

    if not clean_filename.lower().endswith(".pdf"):
        raise ApplicationException(
            message="The uploaded filename must end with .pdf.",
            status_code=400,
            error_code="INVALID_FILENAME",
        )

    normalized_content_type = (
        content_type.strip().lower() if content_type else None
    )

    if normalized_content_type and normalized_content_type not in ALLOWED_CONTENT_TYPES:
        raise ApplicationException(
            message="The declared file type is not acceptable.",
            status_code=400,
            error_code="INVALID_CONTENT_TYPE",
            details={"content_type": normalized_content_type},
        )

    if not file_bytes.startswith(PDF_SIGNATURE):
        raise ApplicationException(
            message="The uploaded content does not have a valid PDF signature.",
            status_code=400,
            error_code="INVALID_PDF_SIGNATURE",
        )

    try:
        reader = PdfReader(BytesIO(file_bytes), strict=False)
        _ = len(reader.pages)
    except Exception as exc:
        raise ApplicationException(
            message="The uploaded file could not be opened as a valid PDF.",
            status_code=400,
            error_code="CORRUPTED_PDF",
        ) from exc

    return PdfValidationResult(
        filename=clean_filename,
        content_type=normalized_content_type,
        size_bytes=len(file_bytes),
    )
