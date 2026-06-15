#!/usr/bin/env python3
"""
Convert the Markdown deliverables in this folder to submittable formats:
  - reports  -> .docx   (pandoc)
  - slides   -> .pptx   (pandoc, slide-level 2)

Requires pandoc, supplied by the `pypandoc-binary` wheel (bundles its own pandoc):
    python -m pip install --user pypandoc-binary

Run from the repo root (or anywhere):
    python deliverables/build_deliverables.py

Outputs land next to the source Markdown in this folder.
"""
import os
import re
import sys
import tempfile

try:
    import pypandoc
except ImportError:
    sys.exit("pypandoc not installed. Run: python -m pip install --user pypandoc-binary")

HERE = os.path.dirname(os.path.abspath(__file__))

# Every deliverable except the slide deck is a prose report -> .docx
REPORTS = [
    "system-architecture-and-ui-design.md",
    "implementation-1-report.md",
    "software-demo-1-video-script.md",
    "final-presentation-video-script.md",
    "implementation-2-report.md",
    "software-implementation-2-video-script.md",
    "final-report.md",
]
SLIDES = "final-presentation-slides.md"

ok, failed = [], []


def convert_reports():
    for name in REPORTS:
        src = os.path.join(HERE, name)
        out = os.path.join(HERE, name[:-3] + ".docx")
        try:
            pypandoc.convert_file(src, "docx", format="gfm", outputfile=out,
                                  extra_args=["--standalone", f"--resource-path={HERE}"])
            ok.append(os.path.basename(out))
        except Exception as exc:  # noqa: BLE001 - report and continue
            failed.append(f"{name}: {exc}")


def convert_slides():
    """The Marp deck uses YAML front-matter, HTML-comment notes, and `---` slide
    separators. Strip those so pandoc's pptx writer drives slides off the `##`
    headings (slide level 2) and produces a clean, *editable* deck (no Chrome needed)."""
    src = os.path.join(HERE, SLIDES)
    if not os.path.exists(src):
        failed.append(f"{SLIDES}: not found")
        return
    text = open(src, encoding="utf-8").read()
    text = re.sub(r"^---\n.*?\n---\n", "", text, count=1, flags=re.DOTALL)  # front-matter
    text = re.sub(r"<!--.*?-->", "", text, flags=re.DOTALL)                # comment notes
    text = "\n".join(l for l in text.splitlines() if l.strip() != "---")   # slide separators
    tmp = tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8")
    tmp.write(text)
    tmp.close()
    out = os.path.join(HERE, "final-presentation.pptx")
    try:
        pypandoc.convert_file(tmp.name, "pptx", format="gfm",
                              outputfile=out, extra_args=["--slide-level=2"])
        ok.append(os.path.basename(out))
    except Exception as exc:  # noqa: BLE001
        failed.append(f"{SLIDES}: {exc}")
    finally:
        os.unlink(tmp.name)


if __name__ == "__main__":
    convert_reports()
    convert_slides()
    print("Generated:")
    for f in ok:
        print("  +", f)
    if failed:
        print("FAILED:")
        for f in failed:
            print("  !", f)
        sys.exit(1)
    print(f"\n{len(ok)} file(s) written to {HERE}")
