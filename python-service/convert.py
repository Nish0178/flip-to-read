"""
PDF -> page images converter (called by the Java app).

Usage:  python convert.py <input.pdf> <output_dir> [dpi]
Output: output_dir/page-1.jpg, page-2.jpg, ... and meta.json
"""
import json
import os
import sys
import warnings

# Suppress deprecation warnings so stdout/stderr stay clean
warnings.filterwarnings("ignore")

try:
    import pymupdf as fitz
except ImportError:
    import fitz


def main() -> int:
    if len(sys.argv) < 3:
        print("Usage: convert.py <input.pdf> <output_dir> [dpi]", file=sys.stderr)
        return 1

    pdf_path, out_dir = sys.argv[1], sys.argv[2]
    dpi = int(sys.argv[3]) if len(sys.argv) > 3 else 110
    os.makedirs(out_dir, exist_ok=True)

    try:
        doc = fitz.open(pdf_path)
    except Exception as exc:
        print(f"Cannot open PDF: {exc}", file=sys.stderr)
        return 2

    try:
        if doc.needs_pass:
            print("PDF is password protected.", file=sys.stderr)
            return 3
        if doc.page_count == 0:
            print("PDF has no pages.", file=sys.stderr)
            return 4

        zoom = dpi / 72.0
        matrix = fitz.Matrix(zoom, zoom)
        width = height = 0

        for number, page in enumerate(doc, start=1):
            pix = page.get_pixmap(matrix=matrix, alpha=False)
            if number == 1:
                width, height = pix.width, pix.height
            pix.save(os.path.join(out_dir, f"page-{number}.jpg"), jpg_quality=85)

        meta = {"pages": doc.page_count, "width": width, "height": height}
        with open(os.path.join(out_dir, "meta.json"), "w", encoding="utf-8") as f:
            json.dump(meta, f)

        print(json.dumps(meta))
        return 0
    finally:
        try:
            doc.close()
        except Exception:
            pass


if __name__ == "__main__":
    sys.exit(main())

