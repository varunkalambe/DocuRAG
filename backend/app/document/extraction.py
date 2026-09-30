from io import BytesIO

from pypdf import PdfReader

from app.core.exceptions import ApplicationException
from app.document.models import ExtractedPage


def extract_pdf_pages(file_bytes: bytes) -> list[ExtractedPage]:
    try:
        reader = PdfReader(BytesIO(file_bytes), strict=False)
        pages: list[ExtractedPage] = []

        for index, page in enumerate(reader.pages, start=1):
            try:
                text = page.extract_text() or ""
            except Exception as exc:
                raise ApplicationException(
                    message=f"Text extraction failed for PDF page {index}.",
                    status_code=422,
                    error_code="PAGE_EXTRACTION_FAILED",
                    details={"page_number": index},
                ) from exc

            pages.append(
                ExtractedPage(
                    page_number=index,
                    text=text,
                    is_empty=not bool(text.strip()),
                )
            )

        return pages

    except ApplicationException:
        raise
    except Exception as exc:
        raise ApplicationException(
            message="The PDF could not be processed for text extraction.",
            status_code=422,
            error_code="PDF_EXTRACTION_FAILED",
        ) from exc
