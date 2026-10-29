"""
PDF -> page images converter (called by the Java app).

Usage:  python convert.py <input.pdf> <output_dir> [dpi] [format] [--meta-only]
Output: output_dir/page-1.jpg, page-2.jpg, ... and meta.json
"""
import json
import os
import sys
import threading
import warnings
from concurrent.futures import ThreadPoolExecutor

# Suppress deprecation warnings so stdout/stderr stay clean
warnings.filterwarnings("ignore")

try:
    import pymupdf as fitz
except ImportError:
    import fitz


def main() -> int:
    meta_only = "--meta-only" in sys.argv
    pos_args = [a for a in sys.argv[1:] if a != "--meta-only"]

    if len(pos_args) < 2:
        print("Usage: convert.py <input.pdf> <output_dir> [dpi] [format] [--meta-only]", file=sys.stderr)
        return 1

    pdf_path, out_dir = pos_args[0], pos_args[1]
    env_dpi = os.environ.get("APP_DPI", "180")
    try:
        default_dpi = int(env_dpi)
    except ValueError:
        default_dpi = 180
    dpi = int(pos_args[2]) if len(pos_args) > 2 else default_dpi

    raw_format = pos_args[3] if len(pos_args) > 3 else os.environ.get("APP_IMAGE_FORMAT", "jpg")
    img_format = "png" if raw_format.lower() in ("png", "image/png") else "jpg"

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
        first_page = doc[0]
        rect = first_page.rect
        width = int(round(rect.width * zoom))
        height = int(round(rect.height * zoom))

        meta = {
            "pages": doc.page_count,
            "width": width,
            "height": height,
            "dpi": dpi,
            "format": img_format
        }

        meta_file = os.path.join(out_dir, "meta.json")
        # Preserve enriched fields if already present
        if os.path.isfile(meta_file):
            try:
                with open(meta_file, "r", encoding="utf-8") as f:
                    existing = json.load(f)
                if isinstance(existing, dict):
                    existing.update(meta)
                    meta = existing
            except Exception:
                pass

        with open(meta_file, "w", encoding="utf-8") as f:
            json.dump(meta, f)

        if meta_only:
            print(json.dumps(meta))
            return 0

        # Full rasterization phase with CPU-aware bounded concurrency
        matrix = fitz.Matrix(zoom, zoom)
        total_pages = doc.page_count

        cpu_cnt = os.cpu_count() or 2
        workers = min(4, max(1, cpu_cnt))

        thread_data = threading.local()

        def get_worker_doc():
            if not hasattr(thread_data, "doc"):
                thread_data.doc = fitz.open(pdf_path)
            return thread_data.doc

        def render_page(pageNum: int):
            worker_doc = get_worker_doc()
            page = worker_doc[pageNum - 1]
            pix = page.get_pixmap(matrix=matrix, alpha=False)
            out_file = os.path.join(out_dir, f"page-{pageNum}.{img_format}")
            if img_format == "png":
                pix.save(out_file)
            else:
                pix.save(out_file, jpg_quality=85)
            del pix

        if workers > 1 and total_pages > 1:
            with ThreadPoolExecutor(max_workers=workers) as executor:
                list(executor.map(render_page, range(1, total_pages + 1)))
        else:
            for p in range(1, total_pages + 1):
                render_page(p)

        print(json.dumps(meta))
        return 0
    finally:
        try:
            doc.close()
        except Exception:
            pass


if __name__ == "__main__":
    sys.exit(main())

