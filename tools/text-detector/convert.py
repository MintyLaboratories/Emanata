# /// script
# requires-python = ">=3.10,<3.13"
# dependencies = [
#     "paddlepaddle==3.1.0",
#     "paddle2onnx==2.1.0",
#     "onnx==1.17.0",
#     "pnnx==20261020",
# ]
# ///
"""Converts PaddleOCR's PP-OCRv5 mobile text detector to ncnn files the app can run.

Run from the repository root:

    uv run tools/text-detector/convert.py [--work <dir>]

Downloads the official model (Apache-2.0) from Hugging Face at a pinned revision and checks
its SHA-256, exports it to ONNX with paddle2onnx, converts that to ncnn with pnnx (fp16
weights), and writes `build/text-detector/ppocrv5-mobile-det.ncnn.{param,bin}` and the
`NOTICE` (Apache-2.0 §4(b)) stating what changed: a conversion of the app's own, to host in
place of the third party's the app downloads (`ModelCatalog.TEXT_DETECTOR`).
Weights are unchanged but for fp16 storage. One edit to pnnx's output: its squeeze-and-excite
reshapes carry size expressions the app's ncnn release cannot evaluate when the model loads;
they become plain reshapes to 1 x 1 x channels, which is what they compute. Input `in0` is
B, G, R normalized with ImageNet's mean and deviation; output `out0` is the text score map at
the input's size.
"""
import argparse
import hashlib
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request

REPO = 'https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det/resolve/0d63e78e2b680928f6b1747d76a08db6e645efb7/'
FILES = {
    'inference.json': '05feef1acb00aa4cd7362b15f7f501fc4f99d7b1fa73c1c871e0c7b1504b0f5c',
    'inference.pdiparams': 'afa1820cb16c1fd0dad589d0f8b389139061c1ef6d68019685fd07be997dda5b',
    'inference.yml': None,
}
OUT = pathlib.Path('build/text-detector')
NAME = 'ppocrv5-mobile-det.ncnn'

# pnnx's squeeze-and-excite reshape to [1, C, 1, 1], written as an expression on the batch size.
EXPRESSION_RESHAPE = re.compile(rb'12=0 13=0 6="1,1,//\(\d+,0n\),0n"')

NOTICE = """PP-OCRv5 mobile text detection model (PP-OCRv5_mobile_det) by the PaddlePaddle authors
Licensed under the Apache License, Version 2.0
Source: https://github.com/PaddlePaddle/PaddleOCR
Weights: https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det (revision {revision})

Modified for this app by tools/text-detector/convert.py:
- converted to ONNX (paddle2onnx), then to ncnn (pnnx)
- weights stored in fp16
- {edits} squeeze-and-excite reshape layers rewritten from size expressions to plain reshapes to 1 x 1 x channels
"""


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*args: str, cwd: pathlib.Path) -> None:
    subprocess.run(args, cwd=cwd, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--work', type=pathlib.Path, help='folder for downloads and intermediates (default: temporary)')
    args = parser.parse_args()
    work = args.work or pathlib.Path(tempfile.mkdtemp())
    model = work / 'model'
    model.mkdir(parents=True, exist_ok=True)
    for name, digest in FILES.items():
        target = model / name
        if not target.is_file():
            urllib.request.urlretrieve(REPO + name, target)
        if digest and sha256(target) != digest:
            print(f'{name}: SHA-256 differs', file=sys.stderr)
            return 1
    scripts = pathlib.Path(sys.executable).parent
    run(str(scripts / 'paddle2onnx'), '--model_dir', str(model), '--model_filename', 'inference.json',
        '--params_filename', 'inference.pdiparams', '--save_file', 'det.onnx', '--opset_version', '14', cwd=work)
    run(str(scripts / 'pnnx'), 'det.onnx', 'inputshape=[1,3,320,320]', 'inputshape2=[1,3,256,256]', cwd=work)
    param = (work / 'det.ncnn.param').read_bytes()
    param, edits = EXPRESSION_RESHAPE.subn(b'0=1 1=1 2=-1', param)
    if edits == 0 or b'6="' in param:
        print('unexpected pnnx output: reshapes differ', file=sys.stderr)
        return 1
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f'{NAME}.param').write_bytes(param)
    shutil.copyfile(work / 'det.ncnn.bin', OUT / f'{NAME}.bin')
    (OUT / 'NOTICE').write_text(NOTICE.format(revision=REPO.rstrip('/').rsplit('/', 1)[1], edits=edits), newline='\n')
    for f in sorted(OUT.iterdir()):
        print(f'{f.name}: {f.stat().st_size} bytes, SHA-256 {sha256(f)}')
    if not args.work:
        shutil.rmtree(work, ignore_errors=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
